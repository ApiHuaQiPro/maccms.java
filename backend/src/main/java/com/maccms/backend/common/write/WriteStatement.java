package com.maccms.backend.common.write;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写 SQL 规范的语法部分（B07，docs/architecture/12-write-infrastructure.md 第 4.1 节）：从一条写语句识别操作与目标表，
 * 不符合规范的直接拒绝。静态护栏（写 Mapper XML，{@code #{}} 换成 {@code ?} 后）与运行期拦截器（MyBatis 生成的实际 SQL）
 * 用的是同一个解析。
 *
 * <p>只接受三种单表语句：
 * <ul>
 * <li>{@code INSERT INTO <表> (...) VALUES (...)}，或条件插入 {@code INSERT INTO <表> (...) SELECT ... WHERE NOT EXISTS (...)}；</li>
 * <li>{@code UPDATE <表> [别名] SET ... WHERE ...}；</li>
 * <li>{@code DELETE FROM <表> WHERE ...}。</li>
 * </ul>
 * 拒绝：其他语句（含 REPLACE、SELECT、结构与维护语句）、分号（多语句）、注释、{@code IGNORE}（吞掉错误）、
 * {@code ON DUPLICATE KEY UPDATE}（旧表几乎没有可依赖的唯一键）、多表 UPDATE/DELETE、带库名前缀的表名、
 * 没有顶层 {@code WHERE} 的 UPDATE/DELETE（整表写入）。字符串字面量先整体遮蔽，其中的字符不参与判断。
 */
public record WriteStatement(WriteOperation operation, String table) {

    private static final Pattern INSERT = Pattern.compile(
            "^INSERT\\s+(?:(?:LOW_PRIORITY|DELAYED|HIGH_PRIORITY)\\s+)?(IGNORE\\s+)?INTO\\s+(`?)([A-Za-z0-9_]+)\\2(?=[\\s(]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE = Pattern.compile(
            "^UPDATE\\s+(?:LOW_PRIORITY\\s+)?(IGNORE\\s+)?(`?)([A-Za-z0-9_]+)\\2\\s+(?:(?:AS\\s+)?[A-Za-z0-9_]+\\s+)?SET\\s",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE = Pattern.compile(
            "^DELETE\\s+(?:LOW_PRIORITY\\s+)?(?:QUICK\\s+)?(IGNORE\\s+)?FROM\\s+(`?)([A-Za-z0-9_]+)\\2(?=\\s|$)",
            Pattern.CASE_INSENSITIVE);

    public WriteStatement {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(table, "table");
    }

    /** 解析一条写语句；不符合规范时抛 {@link WriteRejectedException}（message 只含原因，不含语句内容）。 */
    public static WriteStatement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw reject("空语句");
        }
        String masked = maskLiterals(sql);
        if (masked.indexOf(';') >= 0) {
            throw reject("一次只能执行一条语句（不允许分号）");
        }
        if (masked.contains("/*") || masked.contains("--") || masked.indexOf('#') >= 0) {
            throw reject("写语句中不允许注释或优化器提示");
        }
        String s = masked.strip().replaceAll("\\s+", " ");
        String upper = s.toUpperCase(Locale.ROOT);

        Matcher m = INSERT.matcher(s);
        if (m.find()) {
            if (m.group(1) != null) {
                throw reject("不允许 INSERT IGNORE（会把错误变成警告）");
            }
            if (upper.contains(" ON DUPLICATE KEY UPDATE")) {
                throw reject("不允许 ON DUPLICATE KEY UPDATE（旧表没有可依赖的唯一键，用条件插入）");
            }
            return new WriteStatement(WriteOperation.INSERT, m.group(3).toLowerCase(Locale.ROOT));
        }
        if (upper.startsWith("UPDATE ")) {
            m = UPDATE.matcher(s);
            if (!m.find()) {
                throw reject("只允许单表 UPDATE <表> SET ... WHERE ...（不允许多表、JOIN、库名前缀）");
            }
            if (m.group(1) != null) {
                throw reject("不允许 UPDATE IGNORE");
            }
            requireTopLevelWhere(upper, m.end());
            return new WriteStatement(WriteOperation.UPDATE, m.group(3).toLowerCase(Locale.ROOT));
        }
        if (upper.startsWith("DELETE ")) {
            m = DELETE.matcher(s);
            if (!m.find()) {
                throw reject("只允许单表 DELETE FROM <表> WHERE ...（不允许多表、库名前缀）");
            }
            if (m.group(1) != null) {
                throw reject("不允许 DELETE IGNORE");
            }
            if (!upper.substring(m.end()).stripLeading().startsWith("WHERE ")) {
                throw reject("DELETE FROM <表> 之后必须直接是 WHERE（不允许 USING、JOIN、多表、别名）");
            }
            requireTopLevelWhere(upper, m.end());
            return new WriteStatement(WriteOperation.DELETE, m.group(3).toLowerCase(Locale.ROOT));
        }
        throw reject("只允许 INSERT、UPDATE、DELETE 写语句");
    }

    private static void requireTopLevelWhere(String upper, int from) {
        int depth = 0;
        for (int i = from; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith("WHERE", i)
                    && (i == 0 || !isWordChar(upper.charAt(i - 1)))
                    && (i + 5 == upper.length() || !isWordChar(upper.charAt(i + 5)))) {
                return;
            }
        }
        throw reject("UPDATE/DELETE 必须有顶层 WHERE 条件（不允许整表写入）");
    }

    private static boolean isWordChar(char c) {
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** 把单引号、双引号字符串字面量换成 {@code ''}（支持反斜线转义与连写引号）；未闭合时拒绝。 */
    static String maskLiterals(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c != '\'' && c != '"') {
                sb.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            boolean closed = false;
            while (j < n) {
                char d = sql.charAt(j);
                if (d == '\\') {
                    j += 2;
                } else if (d == c) {
                    if (j + 1 < n && sql.charAt(j + 1) == c) {
                        j += 2;
                    } else {
                        closed = true;
                        break;
                    }
                } else {
                    j++;
                }
            }
            if (!closed) {
                throw reject("字符串字面量未闭合");
            }
            sb.append("''");
            i = j + 1;
        }
        return sb.toString();
    }

    private static WriteRejectedException reject(String reason) {
        return new WriteRejectedException("写语句不符合规范：" + reason);
    }
}

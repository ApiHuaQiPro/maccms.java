package com.maccms.backend.admin.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插件包 {@code install.sql} 的解析与分类（B29，35 文档第 5 节；旧 {@code Service::importsql}，Service.php:174-199）。
 *
 * <p>先按旧版的方式切块：逐行读（行带换行符），以 {@code --} 或 {@code /*} 开头的行跳过；其余行累加，去掉首尾空白后以 {@code ;} 结尾的行结束一块，
 * 块里的 {@code __PREFIX__} 换成表前缀、{@code INSERT INTO } 换成 {@code INSERT IGNORE INTO }（都不分大小写）；最后没有以 {@code ;} 结束的部分
 * 旧版不执行。旧版用 {@code PDO::exec} 执行每一块——一块里可以有多条语句（PDO 默认模拟预处理，允许多语句），出错一律忽略。
 *
 * <p>新版把每一块再按 MySQL 的词法切成语句（引号、反引号、{@code --}/{@code #}/块注释里的 {@code ;} 不算），逐条看第一个关键字：
 * 只有 {@link #DML} 算数据语句；其他一律视为不能执行的语句（{@code CREATE/ALTER/DROP/RENAME/TRUNCATE} 等结构语句，以及 {@code SET}、
 * {@code SELECT}、{@code GRANT}、{@code LOAD}、无法识别的文本）。MySQL 的可执行注释 {@code /*!…*&#47;}、{@code /*+…*&#47;} 里的内容会被执行，
 * 也算不能执行的语句。只要有一条不能执行，整个插件包拒绝（路线图第 1.2 节零结构变更、B29 行）。
 */
public final class AddonSql {

    /** 允许的数据语句。 */
    public static final Set<String> DML = Set.of("INSERT", "UPDATE", "DELETE", "REPLACE");

    /**
     * 分析结果。
     *
     * @param statements 按旧版替换前缀后的数据语句（顺序同文件）
     * @param rejected   第一条不能执行的语句的关键字（大写；可执行注释为 {@code /*!}，无法识别为 {@code ?}），全部是数据语句时为 null
     */
    public record Analysis(List<String> statements, String rejected) {
        public boolean ok() {
            return rejected == null;
        }
    }

    private AddonSql() {
    }

    /** 分析 {@code install.sql} 的内容（按 UTF-8 解码；表前缀为旧 {@code config('database.prefix')}）。 */
    public static Analysis analyze(byte[] content, String prefix) {
        List<String> out = new ArrayList<>();
        for (String chunk : legacyChunks(new String(content, StandardCharsets.UTF_8), prefix)) {
            for (String stmt : split(chunk)) {
                String kw = keyword(stmt);
                if (kw == null) {
                    continue;
                }
                if (!DML.contains(kw)) {
                    return new Analysis(List.copyOf(out), kw);
                }
                out.add(stmt.strip());
            }
        }
        return new Analysis(List.copyOf(out), null);
    }

    /** 旧版 importsql 的切块与替换（Service.php:178-195）。 */
    static List<String> legacyChunks(String text, String prefix) {
        List<String> chunks = new ArrayList<>();
        StringBuilder temp = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            String line = nl < 0 ? text.substring(i) : text.substring(i, nl + 1);
            i = nl < 0 ? text.length() : nl + 1;
            if (line.startsWith("--") || line.isEmpty() || line.startsWith("/*")) {
                continue;
            }
            temp.append(line);
            String t = phpTrim(line);
            if (!t.isEmpty() && t.charAt(t.length() - 1) == ';') {
                String chunk = replaceIgnoreCase(temp.toString(), "__PREFIX__", prefix);
                chunks.add(replaceIgnoreCase(chunk, "INSERT INTO ", "INSERT IGNORE INTO "));
                temp.setLength(0);
            }
        }
        return chunks;
    }

    /** PHP {@code trim}：去掉首尾的空格、\t、\n、\r、\0、\x0B。 */
    private static String phpTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && " \t\n\r\0\u000B".indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && " \t\n\r\0\u000B".indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    /** PHP {@code str_ireplace}（ASCII 不分大小写）。 */
    static String replaceIgnoreCase(String s, String search, String replace) {
        String lower = s.toLowerCase(Locale.ROOT);
        String needle = search.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int from = 0;
        int at;
        while ((at = lower.indexOf(needle, from)) >= 0) {
            sb.append(s, from, at).append(replace);
            from = at + search.length();
        }
        return sb.append(s.substring(from)).toString();
    }

    /** 按 MySQL 词法以 {@code ;} 切成语句；可执行注释原样保留在语句里（{@link #keyword} 据此拒绝）。 */
    static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = quoted(sql, i);
                cur.append(sql, i, end);
                i = end;
            } else if (c == '#' || (sql.startsWith("--", i) && (i + 2 == n || Character.isWhitespace(sql.charAt(i + 2))))) {
                int nl = sql.indexOf('\n', i);
                int end = nl < 0 ? n : nl + 1;
                cur.append(' ');
                i = end;
            } else if (c == '/' && sql.startsWith("/*", i)) {
                int close = sql.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                if (sql.startsWith("/*!", i) || sql.startsWith("/*+", i)) {
                    cur.append(sql, i, end);
                } else {
                    cur.append(' ');
                }
                i = end;
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 引号串的结束位置（含结束引号；反斜杠转义与连写两个引号都算串内；没有结束引号时到文本末尾）。 */
    private static int quoted(String s, int start) {
        char q = s.charAt(start);
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && q != '`') {
                i += 2;
                continue;
            }
            if (c == q) {
                if (i + 1 < s.length() && s.charAt(i + 1) == q) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    /** 语句的第一个关键字（大写）；空语句为 null；可执行注释为 {@code /*!}；不是字母开头为 {@code ?}。 */
    static String keyword(String stmt) {
        String s = stmt.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains("/*!") || s.contains("/*+")) {
            return "/*!";
        }
        int i = 0;
        while (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        return i == 0 ? "?" : s.substring(0, i).toUpperCase(Locale.ROOT);
    }
}

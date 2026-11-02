package com.maccms.backend.admin.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库工具“执行 SQL”（DBT-2）的整段判定（技术设计 v13 §4.13 DEC-13、§6.13 DBT-2 ①～④，C-53；契约 API-013）。输入按 PHP
 * {@code Database::sql}（Database.php:237-271）当作<b>一整段</b>处理，不切分、不逐条执行：
 * <ol>
 * <li>PHP {@code trim}；为空（含 PHP {@code empty("0")}）→ {@link Verdict#EMPTY}，不执行；{@code {pre}} 换成表前缀；</li>
 * <li>PHP 跳过判定逐字照搬（{@link #phpSkipNormal}）：以 {@code select} 开头，或含 {@code outfile}/{@code dumpfile}/{@code load_file}
 * → {@link Verdict#SKIPPED}，不发送，提示同 PHP 的“执行成功”；</li>
 * <li>多语句：按 MySQL 词法（单引号、双引号、反引号字符串与 {@code \} 转义；{@code -- }、{@code #}、{@code /* *}{@code /} 注释），第一个不在字符串与
 * 注释里的 {@code ;} 之后还有非空白、非注释内容 → {@link Verdict#MULTI_STATEMENT}；末尾单个 {@code ;} 允许并原样发送；</li>
 * <li>AC-11 结构保护（OP-6 已确认的差异）：去掉注释后取首个关键字，只允许 {@code INSERT}/{@code UPDATE}/{@code DELETE}；出现 {@code /*!}
 * 版本注释视为不可识别 → {@link Verdict#STRUCTURE_REFUSED}。</li>
 * </ol>
 * 本类的源码含拒绝关键字表，是 {@code SchemaGuardTest} 登记的例外（C-55，AC-11）：这里只<b>判定</b>，从不执行任何结构语句。
 */
public final class SqlStatements {

    /** 契约 SqlForm：整段超过 1 MiB → 不发送（DBT_SQL_STRUCTURE_REFUSED）。 */
    public static final int MAX_SQL_BYTES = 1048576;

    public enum Verdict {
        /** trim 后为空：不执行，返回 run_ok。 */
        EMPTY,
        /** PHP 跳过判定命中：不发送，返回 run_ok。 */
        SKIPPED,
        /** 一次只能执行一条语句。 */
        MULTI_STATEMENT,
        /** 结构类操作（AC-11）或超长。 */
        STRUCTURE_REFUSED,
        /** 通过：整段一次发送。 */
        EXECUTE
    }

    /**
     * @param sql       {@code {pre}} 已替换、PHP trim 后的整段文本（只在 {@link Verdict#EXECUTE} 时用于发送）
     * @param singleRow 是否为只影响一行的 {@code INSERT}（单个值组或 {@code SET} 形式、无 {@code SELECT}）：服务端非连接类错误时据此判“确定未生效”
     */
    public record Prepared(Verdict verdict, String sql, boolean singleRow) {
    }

    private static final Pattern PHP_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern PHP_LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern PHP_SPACES = Pattern.compile("[ \\t\\n\\x0B\\f\\r]+");
    private static final Pattern FIRST_WORD = Pattern.compile("^[A-Za-z_]+");

    private SqlStatements() {
    }

    /** 按 DBT-2 ①～④ 判定整段输入。{@code prefix} 为旧 {@code config('database.prefix')}。 */
    public static Prepared prepare(String input, String prefix) {
        if (input != null && input.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            return new Prepared(Verdict.STRUCTURE_REFUSED, null, false);
        }
        String sql = input == null ? "" : LegacyPhp.trim(input);
        if (sql.isEmpty() || sql.equals("0")) {
            return new Prepared(Verdict.EMPTY, null, false);
        }
        sql = sql.replace("{pre}", prefix);
        String norm = phpSkipNormal(sql);
        if (norm.startsWith("select") || norm.contains("outfile") || norm.contains("dumpfile") || norm.contains("load_file")) {
            return new Prepared(Verdict.SKIPPED, null, false);
        }
        Lexed lexed = lex(sql);
        int semi = lexed.code.indexOf(';');
        if (semi >= 0 && !lexed.code.substring(semi + 1).isBlank()) {
            return new Prepared(Verdict.MULTI_STATEMENT, null, false);
        }
        if (lexed.versionComment) {
            return new Prepared(Verdict.STRUCTURE_REFUSED, null, false);
        }
        Matcher m = FIRST_WORD.matcher(lexed.code.stripLeading());
        String keyword = m.find() ? m.group().toUpperCase(Locale.ROOT) : "";
        if (!keyword.equals("INSERT") && !keyword.equals("UPDATE") && !keyword.equals("DELETE")) {
            return new Prepared(Verdict.STRUCTURE_REFUSED, null, false);
        }
        return new Prepared(Verdict.EXECUTE, sql, keyword.equals("INSERT") && singleRowInsert(lexed.code));
    }

    /**
     * PHP 跳过判定的规范化（Database.php:252-255，逐字照搬）：{@code strtolower}（只改 A-Z）、{@code #/\*.*?\*}{@code /#s} 换空格、
     * {@code /--[^\n]*}{@code /} 换空格、{@code trim} 后 {@code /\s+/} 压成一个空格。
     */
    static String phpSkipNormal(String sql) {
        StringBuilder lower = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        String norm = PHP_BLOCK_COMMENT.matcher(lower).replaceAll(" ");
        norm = PHP_LINE_COMMENT.matcher(norm).replaceAll(" ");
        return PHP_SPACES.matcher(LegacyPhp.trim(norm)).replaceAll(" ");
    }

    /** 词法结果：{@code code} 中字符串换成一个 {@code S}、注释换成空格，其余原样；{@code versionComment} 为出现过 {@code /*!}。 */
    private record Lexed(String code, boolean versionComment) {
    }

    private static Lexed lex(String sql) {
        StringBuilder code = new StringBuilder(sql.length());
        boolean version = false;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                code.append('S');
            } else if (c == '#') {
                i = lineEnd(sql, i);
                code.append(' ');
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' && (i + 2 >= n || sql.charAt(i + 2) <= ' ')) {
                i = lineEnd(sql, i);
                code.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                if (i + 2 < n && sql.charAt(i + 2) == '!') {
                    version = true;
                }
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                code.append(' ');
            } else {
                code.append(c);
                i++;
            }
        }
        return new Lexed(code.toString(), version);
    }

    /** 从开引号 {@code at} 起跳过整个字符串，返回其后的位置；单双引号内 {@code \} 转义下一个字符，反引号不转义；连续两个引号是字面引号（自然地视为关闭再打开）。 */
    private static int skipQuoted(String sql, int at, char quote) {
        int i = at + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return sql.length();
    }

    private static int lineEnd(String sql, int at) {
        int end = sql.indexOf('\n', at);
        return end < 0 ? sql.length() : end;
    }

    private static final Pattern SELECT = Pattern.compile("\\bSELECT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUES = Pattern.compile("\\bVALUES?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET = Pattern.compile("\\bSET\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_DUPLICATE = Pattern.compile("\\bON\\s+DUPLICATE\\b", Pattern.CASE_INSENSITIVE);

    /** 只影响一行的 INSERT：无 SELECT，且 VALUES 后恰好一个顶层值组，或 SET 形式。字符串已被词法替换，括号与关键字只来自语句本身。 */
    private static boolean singleRowInsert(String code) {
        if (SELECT.matcher(code).find()) {
            return false;
        }
        Matcher v = VALUES.matcher(code);
        if (!v.find()) {
            return SET.matcher(code).find();
        }
        String rest = code.substring(v.end());
        Matcher dup = ON_DUPLICATE.matcher(rest);
        if (dup.find()) {
            rest = rest.substring(0, dup.start());
        }
        int depth = 0;
        int groups = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    groups++;
                }
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return groups == 1;
    }
}

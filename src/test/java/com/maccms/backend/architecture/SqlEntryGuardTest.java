package com.maccms.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * SQL 入口护栏（B02，路线图 B02 ⑤；docs/architecture/11-guards-and-legacy-config.md 第 3.3 节）：所有 SQL 都是代码里写死的
 * 参数化语句，不存在把请求内容当作整条 SQL 执行的路径。
 * <ol>
 * <li>JDBC/Spring JDBC 的执行入口（{@code Statement.execute*}、{@code addBatch(String)}、{@code prepareStatement}、
 * {@code prepareCall}、{@code nativeSQL}、{@code JdbcTemplate.query*}/{@code update}/{@code batchUpdate}/{@code execute}、
 * {@code JdbcClient.sql}）的第一个参数只能是字符串字面量、常量（全大写标识符，可带类名限定）或它们的 {@code +} 拼接。</li>
 * <li>MyBatis XML 与注解中的 {@code ${}} 只允许 {@link #REGISTERED_DOLLAR} 登记的表达式（各批文档登记的白名单标识符：
 * 经 {@code SHOW COLUMNS}/表清单校验的表名、列名，排序方向）。B02 交付时登记表为空。</li>
 * <li>不允许动态拼 SQL 的 MyBatis 入口：{@code @SelectProvider} 等 Provider 注解、{@code org.apache.ibatis.jdbc}
 * （{@code SQL}、{@code SqlRunner}、{@code ScriptRunner}）。</li>
 * </ol>
 * 只检查引用了 JDBC/Spring JDBC/MyBatis 包的源文件（其他文件里的 {@code execute(}、{@code update(} 与 SQL 无关）。
 * 规则由 {@link #rulesOnSyntheticSources()} 自检，"把请求参数拼成整条 SQL 执行"的合成用例必须被拦下。
 */
class SqlEntryGuardTest {

    /** 允许出现在 {@code ${...}} 里的表达式 → 登记批次与校验方式。B02 交付时为空。 */
    static final Map<String, String> REGISTERED_DOLLAR = Map.of(
            "table", "B15 数据库工具（24 文档第 9.2 节）：必须出现在 SHOW TABLE STATUS 的结果里且只含字母数字下划线",
            "columns", "B15 数据库工具（24 文档第 9.3 节）：SHOW COLUMNS 的列名逐个校验（只含字母数字下划线）后拼成 CAST(`列` AS CHAR) AS `列`");

    private static final Path MAPPERS = JavaSources.MAIN_RESOURCES.resolve("mapper");

    private static final Pattern DATA_ACCESS = Pattern.compile(
            "\\b(?:java\\.sql|javax\\.sql|org\\.springframework\\.jdbc|org\\.apache\\.ibatis|org\\.mybatis)\\.");
    private static final Set<String> SQL_METHODS = Set.of("execute", "executeQuery", "executeUpdate",
            "executeLargeUpdate", "executeLargeBatch", "addBatch", "prepareStatement", "prepareCall", "nativeSQL",
            "query", "queryForList", "queryForObject", "queryForMap", "queryForRowSet", "queryForStream", "update",
            "batchUpdate", "sql");
    private static final Pattern CALL = Pattern.compile("\\.\\s*([A-Za-z]\\w*)\\s*\\(");
    /** 常量参数：字面量（{@code ""}，字符串已被预处理清空）、全大写常量（可带类名限定），以及它们的 + 拼接。 */
    private static final Pattern CONSTANT_ARG = Pattern.compile(
            "\\s*(?:\"\"|(?:[A-Za-z_]\\w*\\.)*[A-Z][A-Z0-9_]*)(?:\\s*\\+\\s*(?:\"\"|(?:[A-Za-z_]\\w*\\.)*[A-Z][A-Z0-9_]*))*\\s*");
    private static final Pattern FORBIDDEN_MYBATIS = Pattern.compile(
            "\\b(?:org\\.apache\\.ibatis\\.jdbc\\.|(?:Select|Insert|Update|Delete)Provider\\b|SqlRunner\\b|ScriptRunner\\b)");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{([^}]*)}");

    @Test
    void mainSourcesOnlyExecuteConstantSql() throws IOException {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, String> e : JavaSources.mainJava().entrySet()) {
            for (String v : javaViolations(e.getValue(), REGISTERED_DOLLAR)) {
                all.add(e.getKey() + ": " + v);
            }
        }
        assertThat(all).as("SQL 入口护栏").isEmpty();
    }

    @Test
    void mapperXmlUsesOnlyRegisteredDollarExpressions() throws IOException {
        // 只读 Mapper（mapper/）与写 Mapper（mapper-write/，B07）都检查
        Map<String, String> mappers = new java.util.LinkedHashMap<>(JavaSources.read(MAPPERS, ".xml"));
        assertThat(mappers).isNotEmpty();
        JavaSources.read(WriteGuardTest.WRITE_MAPPERS, ".xml").forEach((k, v) -> mappers.put("mapper-write/" + k, v));
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, String> e : mappers.entrySet()) {
            for (String v : xmlViolations(e.getValue(), REGISTERED_DOLLAR)) {
                all.add(e.getKey() + ": " + v);
            }
        }
        assertThat(all).as("Mapper XML 中未登记的 ${}").isEmpty();
    }

    @Test
    void rulesOnSyntheticSources() {
        String okJdbc = """
                package com.maccms.backend.admin.infrastructure;
                import java.sql.Connection;
                import org.springframework.jdbc.core.JdbcTemplate;
                class Ok {
                    static final String COLS = "vod_id, vod_name";
                    static final String SELECT_BY_ID = "SELECT " + COLS + " FROM mac_vod WHERE vod_id = ?";
                    void f(Connection c, JdbcTemplate jdbc, long id) throws Exception {
                        c.prepareStatement(SELECT_BY_ID).setLong(1, id);
                        c.prepareStatement("SELECT 1");
                        c.createStatement().executeQuery("SELECT " + COLS + " FROM mac_vod");
                        jdbc.queryForList(Ok.SELECT_BY_ID, id);
                        jdbc.update("UPDATE mac_vod SET vod_hits = vod_hits + 1 WHERE vod_id = ?", id);
                        java.util.List.of(1).forEach(x -> {});
                    }
                }
                """;
        assertThat(javaViolations(okJdbc, Map.of())).isEmpty();
        // 不引用数据访问包的文件不检查（例如线程池的 execute(task)）
        assertThat(javaViolations("""
                package com.maccms.backend.content.application;
                class Ok { void f(java.util.concurrent.Executor e, Runnable r) { e.execute(r); } }
                """, Map.of())).isEmpty();

        // "把请求参数拼成整条 SQL 执行"
        for (String bad : List.of(
                "void f(Connection c, String q) throws Exception { c.createStatement().execute(q); }",
                "void f(Connection c, String q) throws Exception { c.createStatement().executeQuery(q); }",
                "void f(Connection c, String q) throws Exception { c.createStatement().executeUpdate(q); }",
                "void f(java.sql.Statement st, String q) throws Exception { st.addBatch(q); }",
                "void f(Connection c, String q) throws Exception { c.prepareStatement(q); }",
                "void f(Connection c, String q) throws Exception { c.prepareCall(q); }",
                "void f(Connection c, String id) throws Exception { c.prepareStatement(\"SELECT * FROM mac_vod WHERE vod_id=\" + id); }",
                "void f(JdbcTemplate j, String q) { j.execute(q); }",
                "void f(JdbcTemplate j, String where) { j.queryForList(\"SELECT vod_id FROM mac_vod WHERE \" + where); }",
                "void f(JdbcTemplate j, String q) { j.update(q); }",
                "void f(JdbcTemplate j, String[] q) { j.batchUpdate(q); }",
                "void f(JdbcTemplate j, javax.servlet.http.HttpServletRequest r) { j.queryForMap(r.getParameter(\"sql\")); }",
                "void f(JdbcTemplate j, String q) { j.query(String.format(\"SELECT %s\", q), rs -> {}); }",
                "void f(org.springframework.jdbc.core.simple.JdbcClient c, String q) { c.sql(q).update(); }",
                "void f(JdbcTemplate j, String q) { j.update(SQL_PREFIX + q); }")) {
            String src = "package com.maccms.backend.admin.infrastructure;\nimport java.sql.Connection;\n"
                    + "import org.springframework.jdbc.core.JdbcTemplate;\nclass X { static final String SQL_PREFIX = \"\"; "
                    + bad + " }";
            assertThat(javaViolations(src, Map.of())).as(bad).isNotEmpty();
        }

        // MyBatis：Provider、SQL 构建器、ScriptRunner；注解里的 ${}
        for (String bad : List.of(
                "import org.apache.ibatis.annotations.SelectProvider;",
                "interface M { @org.apache.ibatis.annotations.UpdateProvider(type = P.class) void u(); }",
                "import org.apache.ibatis.jdbc.SQL;",
                "import org.apache.ibatis.jdbc.ScriptRunner;",
                "import org.apache.ibatis.jdbc.SqlRunner;",
                "interface M { @org.apache.ibatis.annotations.Select(\"SELECT ${col} FROM mac_vod\") java.util.List<Object> q(); }")) {
            String src = "package com.maccms.backend.content.infrastructure;\n" + bad;
            assertThat(javaViolations(src, Map.of())).as(bad).isNotEmpty();
        }
        assertThat(javaViolations("package com.maccms.backend.content.infrastructure;\n"
                + "interface M { @org.apache.ibatis.annotations.Select(\"SELECT ${sortColumn} FROM mac_vod\") Object q(); }",
                Map.of("sortColumn", "B99：SHOW COLUMNS 校验"))).isEmpty();

        // XML：未登记的 ${} 被拦下，登记的允许，注释里的不算
        String xml = "<mapper><!-- ${note} --><select id=\"a\">SELECT vod_id FROM mac_vod ORDER BY ${order}</select></mapper>";
        assertThat(xmlViolations(xml, Map.of())).hasSize(1);
        assertThat(xmlViolations(xml, Map.of("order", "B99：枚举白名单"))).isEmpty();
        assertThat(xmlViolations("<mapper><select id=\"a\">SELECT 1 WHERE a = #{a}</select></mapper>", Map.of())).isEmpty();
    }

    static List<String> javaViolations(String source, Map<String, String> registeredDollar) {
        String code = JavaSources.stripCommentsAndStrings(source);
        List<String> out = new ArrayList<>();
        if (!DATA_ACCESS.matcher(code).find()) {
            return out;
        }
        Matcher f = FORBIDDEN_MYBATIS.matcher(code);
        while (f.find()) {
            out.add("不允许的动态 SQL 入口：" + f.group());
        }
        Matcher c = CALL.matcher(code);
        while (c.find()) {
            if (!SQL_METHODS.contains(c.group(1))) {
                continue;
            }
            String arg = firstArgument(code, c.end());
            if (arg.isBlank() || !CONSTANT_ARG.matcher(arg).matches()) {
                out.add("SQL 执行入口 ." + c.group(1) + "( 的第一个参数不是常量");
            }
        }
        // MyBatis 注解与字符串中的 ${}（需要看字符串内容，所以只去掉注释）
        Matcher d = DOLLAR.matcher(JavaSources.stripComments(source));
        while (d.find()) {
            if (!registeredDollar.containsKey(d.group(1).strip())) {
                out.add("未登记的 ${" + d.group(1) + "}");
            }
        }
        return out;
    }

    static List<String> xmlViolations(String xml, Map<String, String> registeredDollar) {
        List<String> out = new ArrayList<>();
        Matcher d = DOLLAR.matcher(JavaSources.stripXmlComments(xml));
        while (d.find()) {
            if (!registeredDollar.containsKey(d.group(1).strip())) {
                out.add("未登记的 ${" + d.group(1) + "}");
            }
        }
        return out;
    }

    /** 从左括号之后取第一个顶层参数（字符串已被清空，不会含括号或逗号）。 */
    private static String firstArgument(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                if (depth == 0) {
                    return code.substring(from, i);
                }
                depth--;
            } else if (ch == ',' && depth == 0) {
                return code.substring(from, i);
            }
        }
        return code.substring(from);
    }
}

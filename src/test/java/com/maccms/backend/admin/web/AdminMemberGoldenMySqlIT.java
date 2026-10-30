package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.write.WriteDatabase;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.member.application.FinanceAdminService;
import com.maccms.backend.member.application.MemberAdminServices;
import com.maccms.backend.member.application.MemberFinanceSettings;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.JsonReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWriteMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWrites;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * B14 后台会员与财务管理的真实 MySQL 对照（./mvnw verify -Pmysql-it；23 文档第 9 节）。
 *
 * <p>流程同 B13 的 {@code AdminContentGoldenMySqlIT}：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql →
 * 取表结构指纹 → 按场景的旧配置各启动一个打开了可写数据源与后台的应用上下文 → 逐个复放 PHP 8.0 与 7.3 的黄金（admin_member_golden.php）：
 * 每个场景先把跟踪的各表清空、复位自增计数并写入场景的行，换新的会话；每个请求前把应用的时钟拨到 PHP 请求开始的秒，按同样的请求发给新版，
 * 比较状态码、Location、JSON 字节、页面结构（{@code AdminContentHtml}）、CSV 正文与 Content-Disposition、请求后各表的全部行。
 * 写入的时间列：PHP 的值落在请求的 [started, after] 秒内、新版的值等于拨好的秒时视为相同；新生成的卡号与卡密两边各自随机，只比较长度
 * （字符集由 {@code CardAdminServiceTest} 对照旧 {@code mac_get_rndstr}）。有意差异（23 文档第 10 节）按 {@link #JAVA} 等断言新版的结果。
 * 之后是并发与对账（②）、回滚演练（③）。结束时表结构指纹不变，删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminMemberGoldenMySqlIT {

    static final String PHP80 = "admin/member/php80-golden.json";
    static final String PHP73 = "admin/member/php73-golden.json";
    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final String E = "/_admin.php";
    /** 跟踪的表与主键（与黄金脚本的 TABLES 相同）。 */
    static final Map<String, String> TABLES = tables();
    private static final MutableClock CLOCK = new MutableClock(Instant.now().getEpochSecond());

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> schemaAtImport;
    private static Map<String, Map<String, String>> columnKinds;
    private static final Map<String, Variant> VARIANTS = new LinkedHashMap<>();
    private static final List<Path> TEMP_DIRS = new ArrayList<>();
    private static final Map<String, JsonNode> GOLDEN = new HashMap<>();

    record Variant(ConfigurableApplicationContext app, MockMvc mvc, Path legacyDir) {
    }

    private static Map<String, String> tables() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mac_user", "user_id");
        m.put("mac_group", "group_id");
        m.put("mac_card", "card_id");
        m.put("mac_order", "order_id");
        m.put("mac_cash", "cash_id");
        m.put("mac_plog", "plog_id");
        m.put("mac_ulog", "ulog_id");
        m.put("mac_visit", "visit_id");
        m.put("mac_type", "type_id");
        m.put("mac_vod", "vod_id");
        m.put("mac_art", "art_id");
        m.put("mac_topic", "topic_id");
        m.put("mac_actor", "actor_id");
        return m;
    }

    // ------------------------------------------------------------------ 有意差异（23 文档第 10 节）：场景/步骤 → 新版的观察结果

    /** 键为 {@code 场景/步骤}（两版 PHP 都适用）或 {@code 80:场景/步骤}、{@code 73:场景/步骤}（只对该版）。值为新版的观察结果。 */
    static final Map<String, String> JAVA = new HashMap<>();
    /** 有意差异里只差页面局部的步骤：对 PHP 的观察结果依次做文字替换后再比较。 */
    static final Map<String, List<String[]>> JAVA_PATCH = new HashMap<>();
    /**
     * 所有步骤共用的页面差异（对 PHP 的观察结果依次替换后再比较）。D-B13-19（同一规则）：旧模板原样输出库里的文字，其中的标签被浏览器当作标签
     * 解析（下拉框里的 {@code <b>} 被丢掉、单元格里只剩标签外的文字），双引号截断属性值；新版按浏览器看到的文字转义输出，标签显示为文字、属性
     * 值完整。种子里含标签与引号的取值：会员组 4 的名称、会员 6 的用户名与昵称、订单 3 的单号、积分日志 3 的备注、推广访问 3 的来源。
     */
    static final List<String[]> JAVA_GLOBAL = List.of(
            new String[] {"[4=金牌会员]", "[4=金牌<b>会员</b>]"},
            new String[] {"| 金牌会员 |", "| 金牌<b>会员</b> |"},
            new String[] {"| \"x\"& |", "| <b>\"x\"& |"},
            new String[] {"input user_name type=text value=<b> id=user_name placeholder=",
                "input user_name type=text value=<b>\"x\"& id=user_name placeholder="},
            new String[] {"input user_nick_name type=text value=a / ", "input user_nick_name type=text value=a\"b<c>&d placeholder= / "},
            new String[] {"| PAY9 |", "| PAY<i>9</i> |"},
            new String[] {"| +2 | x |", "| +2 | <b>x</b> |"},
            new String[] {"| http://b.test/ |", "| http://b.test/<b> |"});

    /**
     * 所有步骤共用的正则替换：D-B13-21（同一规则）分页脚本里旧版原样写出 {@code $param['page']}（{@code 2.0}），新版按 {@code intval}；
     * D-B14-7 新增会员的表单（没有这一行）里到期时间旧版是 {@code date('Y-m-d H:i:s', null)}：PHP 8 为当前时间、PHP 7.3 为 0 点（1970-01-01
     * 08:00:00），新版按 PHP 7.3。
     */
    static final List<String[]> JAVA_REGEX = List.of(
            new String[] {"curr=([0-9]+)\\.0 url=", "curr=$1 url="},
            new String[] {"input user_end_time type=text value=[0-9: -]{19} id=user_end_time",
                "input user_end_time type=text value=1970-01-01 08:00:00 id=user_end_time"});

    /** 有意差异里旧版写了库、新版拒绝不写的步骤（只用于场景的最后一步）：新版这一步之后的表与上一步相同。 */
    static final Set<String> JAVA_NO_WRITE = new HashSet<>();
    /** 有意差异里旧版删掉、新版保留（补偿插回）的行：PHP 这一步之后的表再加上场景开始时的这些行。 */
    static final Map<String, Map<String, List<Long>>> JAVA_RESTORE = new HashMap<>();

    static {
        String paramErr = "status=200 json={\"code\":0,\"msg\":\"参数错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}";
        String pointsErr = "status=200 json={\"code\":0,\"msg\":\"更新用户积分失败：\",\"data\":\"\",\"url\":\"\",\"wait\":3}";
        // D-B13-3（同一规则）：旧版在 MySQL 严格模式下报错（整页 500）的取值，新版"参数错误"、不写库
        for (String step : List.of("user-save/name-too-long", "user-save/points-bad", "user-save/points-negative",
                "group-save/edit-points-overflow", "card-info/overflow")) {
            JAVA.put(step, paramErr);
        }
        // D-B13-3：group_type 不是数组（PHP 8 join() 报错 500；PHP 7.3 告警后写成 ',,'），新版"参数错误"
        JAVA.put("group-save/type-scalar", paramErr);
        JAVA_NO_WRITE.add("73:group-save/type-scalar");
        // D-B13-4（同一规则）：ids 每项必须是十进制数字（旧版交给 MySQL 隐式转换，abc 当 0、"删除成功"或"审核成功"，什么都不改）
        for (String step : List.of("user-del/bad", "group-del/bad", "card-del/bad", "order-del/bad", "cash-audit/bad", "cash-del/bad",
                "ulog-del/bad", "plog-del/checkbox-blank", "visit-del/checkbox-blank")) {
            JAVA.put(step, paramErr);
        }
        // D-B14-2：已审核的提现再次审核，旧版再写一次审核时间、再扣一次冻结积分；新版跳过
        JAVA_NO_WRITE.add("cash-audit-again/audited");
        // D-B14-1：审核时冻结积分不够，旧版先标为已审核再扣成负数（MySQL 报错 500）；新版撤销这一行的审核，返回"更新用户积分失败："
        JAVA.put("cash-audit/array", pointsErr);
        JAVA_NO_WRITE.add("cash-audit/array");
        JAVA.put("cash-audit-froze-short/short", pointsErr);
        JAVA_NO_WRITE.add("cash-audit-froze-short/short");
        // D-B14-1：删除未审核的提现时冻结积分不够，旧版先删记录再退积分（MySQL 报错 500，记录没了、积分没退）；新版插回这一行
        JAVA.put("cash-del-froze-short/short", pointsErr);
        JAVA_NO_WRITE.add("cash-del-froze-short/short");
        JAVA.put("cash-del/all", pointsErr);
        JAVA_RESTORE.put("cash-del/all", Map.of("mac_cash", List.of(4L)));
    }

    // ------------------------------------------------------------------ 准备与清理

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + HexFormat.of().formatHex(rnd);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法: " + dbName);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8");
            dbCreated = true;
        }
        System.out.println("[mysql-it] created disposable database " + dbName);
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                for (String sql : installStatements()) {
                    st.execute(sql);
                }
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
            columnKinds = columnKinds(c);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        for (Variant v : VARIANTS.values()) {
            v.app().close();
        }
        for (Path dir : TEMP_DIRS) {
            deleteTree(dir);
        }
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            Map<String, String> schemaNow;
            try (Connection c = admin(dbName)) {
                schemaNow = SchemaFingerprint.of(c, dbName);
            }
            try (Connection c = admin(null); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE `" + dbName + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name = '" + dbName + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                }
            }
            System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
            assertThat(SchemaFingerprint.diff(schemaAtImport, schemaNow)).as("表结构与导入 install.sql 时一致").isEmpty();
        }
    }

    /** 场景的旧配置（黄金记录的完整 extra/maccms.php）对应的应用上下文，没有就启动一个。 */
    static Variant variant(String maccmsPhp) throws Exception {
        Variant v = VARIANTS.get(maccmsPhp);
        if (v != null) {
            return v;
        }
        Path dir = Files.createTempDirectory("maccms-b14-legacy");
        TEMP_DIRS.add(dir);
        Path extra = Files.createDirectories(dir.resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("php", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.copy(f, extra.resolve(f.getFileName().toString()));
            }
        }
        Files.writeString(extra.resolve("maccms.php"), maccmsPhp, StandardCharsets.UTF_8);
        Path site = Files.createTempDirectory("maccms-b14-site");
        TEMP_DIRS.add(site);
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser,
                "--maccms.admin.enabled=true", "--maccms.admin.entry=" + E,
                "--maccms.admin.base-url=http://www.example.test/", "--maccms.legacy-config.dir=" + dir,
                "--maccms.upload.dir=" + site.resolve("upload"),
                "--server.port=0", "--server.address=127.0.0.1"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        ConfigurableApplicationContext app;
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.SERVLET)
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("b14TestClock", CLOCK))
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
        v = new Variant(app, mvc, dir);
        VARIANTS.put(maccmsPhp, v);
        return v;
    }

    // ------------------------------------------------------------------ ① 黄金复放

    @Test
    @Order(1)
    void scenariosMatchPhpGolden() throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (String version : List.of(PHP80, PHP73)) {
            JsonNode golden = load(version);
            String tag = version.equals(PHP80) ? "80" : "73";
            String only = System.getProperty("golden.only", "");
            for (JsonNode s : golden.get("scenarios")) {
                if (!only.isEmpty() && java.util.Arrays.stream(only.split(",")).noneMatch(o -> s.get("label").asString().startsWith(o))) {
                    continue;
                }
                compared += replay(tag, s, problems);
            }
        }
        System.out.println("[mysql-it] admin member golden steps compared: " + compared);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "admin-member-golden-problems.txt"), String.join("\n\n", problems),
                StandardCharsets.UTF_8);
        assertThat(problems).as("与 PHP 黄金的差异（有意差异已按 JAVA 断言），详见 target/admin-member-golden-problems.txt")
                .isEmpty();
        assertThat(compared).isGreaterThan(0);
    }

    private int replay(String tag, JsonNode s, List<String> problems) throws Exception {
        String label = s.get("label").asString();
        Variant v = variant(s.get("maccmsPhp").asString());
        seed(s.get("seed"));
        Map<String, List<Map<String, Object>>> rows0 = rows(s.get("rows0"));
        Map<String, List<Map<String, Object>>> lastPhp = rows0;
        Map<String, List<Map<String, Object>>> java0 = javaRows();
        if (!java0.equals(lastPhp)) {
            problems.add(tag + ":" + label + " 场景开始时的行与 PHP 不同\n  php:  " + lastPhp + "\n  java: " + java0);
            return 0;
        }
        Set<Long> seededCards = ids(rows0.get("mac_card"), "card_id");
        Set<String> acceptedTimes = new HashSet<>();
        String sid = null;
        int n = 0;
        for (JsonNode st : s.get("steps")) {
            String step = st.get("label").asString();
            String key = label + "/" + step;
            if (st.has("sql")) {
                try (Connection c = admin(dbName); Statement x = c.createStatement()) {
                    x.execute(st.get("sql").asString());
                }
                lastPhp = merge(lastPhp, st.get("tables"));
                continue;
            }
            long started = st.get("started").asLong();
            long after = st.get("after").asLong();
            CLOCK.set(started);
            MockHttpServletResponse r = send(v, sid, st.get("request"));
            sid = cookie(r, sid);
            String body = r.getContentAsString(StandardCharsets.UTF_8);
            String actual = observe(r.getStatus(), r.getHeader("Location"), r.getHeader("Content-Disposition"), body);
            String expected = JAVA.containsKey(tag + ":" + key) ? JAVA.get(tag + ":" + key)
                    : JAVA.containsKey(key) ? JAVA.get(key) : phpObservation(st);
            if (expected != null && JAVA_PATCH.containsKey(key)) {
                for (String[] fix : JAVA_PATCH.get(key)) {
                    expected = expected.replace(fix[0], fix[1]);
                }
            }
            if (expected != null) {
                for (String[] fix : JAVA_GLOBAL) {
                    expected = expected.replace(fix[0], fix[1]);
                }
                expected = endTime(expected, s, step);
                expected = expected.replaceAll(JAVA_REGEX.get(0)[0], JAVA_REGEX.get(0)[1]);
            }
            if (expected != null && !expected.equals(actual)) {
                problems.add(tag + ":" + key + "\n  expected: " + expected + "\n  actual:   " + actual);
            }
            boolean noWrite = JAVA_NO_WRITE.contains(tag + ":" + key) || JAVA_NO_WRITE.contains(key);
            Map<String, List<Map<String, Object>>> php = noWrite ? lastPhp : merge(lastPhp, st.get("tables"));
            if (JAVA_RESTORE.containsKey(key)) {
                php = restore(php, rows0, JAVA_RESTORE.get(key));
            }
            Map<String, List<Map<String, Object>>> java = javaRows();
            for (String t : TABLES.keySet()) {
                List<Map<String, Object>> p = php.get(t);
                List<Map<String, Object>> j = java.get(t);
                if (t.equals("mac_card")) {
                    p = randomCards(p, seededCards);
                    j = randomCards(j, seededCards);
                }
                List<String> diff = diffRows(p, j, started, after, acceptedTimes);
                if (!diff.isEmpty()) {
                    problems.add(tag + ":" + key + " 表 " + t + "：\n  " + String.join("\n  ", diff));
                }
            }
            lastPhp = php;
            n++;
        }
        return n;
    }

    /**
     * 会员表单里到期时间一栏（D-B14-7，{@link #JAVA_REGEX}）：新增会员、编号不存在时旧版 PHP 8 是"当前时间"、PHP 7.3 是 0 点，新版按 7.3；
     * 编辑已有会员时照常比较。
     */
    private static String endTime(String o, JsonNode s, String step) {
        boolean blankForm = s.get("label").asString().equals("user-info") && (step.equals("new") || step.equals("missing"));
        return blankForm ? o.replaceAll(JAVA_REGEX.get(1)[0], JAVA_REGEX.get(1)[1]) : o;
    }

    /** 新生成的卡（不在场景开始时的行里）：卡号、卡密两边各自随机，只保留长度。 */
    private static List<Map<String, Object>> randomCards(List<Map<String, Object>> rows, Set<Long> seeded) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (seeded.contains((Long) r.get("card_id"))) {
                out.add(r);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(r);
            copy.put("card_no", "RANDOM" + ((String) r.get("card_no")).length() / 2);
            copy.put("card_pwd", "RANDOM" + ((String) r.get("card_pwd")).length() / 2);
            out.add(copy);
        }
        return out;
    }

    private static Map<String, List<Map<String, Object>>> restore(Map<String, List<Map<String, Object>>> php,
                                                                 Map<String, List<Map<String, Object>>> rows0,
                                                                 Map<String, List<Long>> keep) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>(php);
        keep.forEach((t, idList) -> {
            String pk = TABLES.get(t);
            List<Map<String, Object>> list = new ArrayList<>(php.get(t));
            for (Map<String, Object> r : rows0.get(t)) {
                if (idList.contains((Long) r.get(pk)) && list.stream().noneMatch(x -> x.get(pk).equals(r.get(pk)))) {
                    list.add(r);
                }
            }
            list.sort(Comparator.comparing(x -> (Long) x.get(pk)));
            out.put(t, list);
        });
        return out;
    }

    private static Set<Long> ids(List<Map<String, Object>> rows, String pk) {
        Set<Long> out = new HashSet<>();
        rows.forEach(r -> out.add((Long) r.get(pk)));
        return out;
    }

    /** PHP 这一步的观察结果（与 {@link #observe} 同格式）；正文没有记录时为 null（只比较表）。 */
    static String phpObservation(JsonNode st) {
        if (st.get("body") == null || st.get("body").isNull()) {
            return null;
        }
        String body = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
        String location = st.get("location") == null || st.get("location").isNull() ? null : st.get("location").asString();
        String disposition = st.get("disposition") == null || st.get("disposition").isNull() ? null
                : st.get("disposition").asString();
        return observe(st.get("status").asInt(), location, disposition, body);
    }

    /** B13 的观察结果，下载（CSV）另带 Content-Disposition。 */
    static String observe(int status, String location, String disposition, String body) {
        String o = AdminContentGoldenMySqlIT.observe(status, location, body);
        return disposition == null ? o : o + " disposition=" + disposition;
    }

    // ------------------------------------------------------------------ 请求

    private static MockHttpServletResponse send(Variant v, String sid, JsonNode req) throws Exception {
        String method = req.get("method").asString();
        String path = req.get("path").asString();
        String query = req.get("query").asString();
        String uri = E + path + (query.isEmpty() ? "" : "?" + query);
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), java.net.URI.create(uri));
        if (req.get("ajax").asBoolean()) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        req.get("headers").properties().forEach(e -> b.header(e.getKey(), e.getValue().asString()));
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if ("POST".equals(method)) {
            b.contentType("application/x-www-form-urlencoded")
                    .content(req.get("post").asString().getBytes(StandardCharsets.ISO_8859_1));
        }
        return v.mvc().perform(b).andReturn().getResponse();
    }

    private static String cookie(MockHttpServletResponse r, String current) {
        for (String h : r.getHeaders("Set-Cookie")) {
            if (h.startsWith("mac_admin_sid=")) {
                String v = h.substring("mac_admin_sid=".length(), h.indexOf(';'));
                return v.isEmpty() || h.contains("Max-Age=0") ? null : v;
            }
        }
        return current;
    }

    // ------------------------------------------------------------------ ② 并发与对账（23 文档第 9 节）

    private static JsonNode scenario(String label) {
        for (JsonNode s : GOLDEN.computeIfAbsent(PHP80, AdminMemberGoldenMySqlIT::load).get("scenarios")) {
            if (s.get("label").asString().equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException(label);
    }

    private static Variant prepare(String label) throws Exception {
        JsonNode s = scenario(label);
        Variant v = variant(s.get("maccmsPhp").asString());
        seed(s.get("seed"));
        return v;
    }

    static PhpForm form(String urlencoded) {
        return AdminContentActions.form(AdminParams.parse(urlencoded.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** 冻结积分与未审核提现的对账：每个会员的 {@code user_points_froze} 等于他未审核提现的积分合计（23 文档第 6.3 节的对账语句）。 */
    static final String FROZE_RECONCILIATION = "SELECT COUNT(*) FROM (SELECT u.user_id FROM mac_user AS u "
            + "LEFT JOIN mac_cash AS c ON c.user_id = u.user_id AND c.cash_status = 0 "
            + "GROUP BY u.user_id, u.user_points_froze HAVING u.user_points_froze <> COALESCE(SUM(c.cash_points), 0)) AS x";

    private static MemberWalletService wallet(Variant v) {
        return new MemberWalletService(v.app().getBean(MemberFinanceMapper.class), v.app().getBean(MemberFinanceWriteMapper.class),
                MemberFinanceSettings.REPOSITORY, CLOCK, new SecureRandom());
    }

    private static MemberState member(long id) {
        return new MemberState(true, id, "u" + id, 2, 2, null, 0, false);
    }

    private static Map<String, PhpInput.Value> params(String urlencoded) {
        return PhpInput.parse(urlencoded.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * 资金类对账：10 个会员各有 1000 积分，冻结与提现记录一致（每次提现 1 元 = 100 积分，仓库配置的比例与下限）。审核只挑编号为偶数的提现、
     * 撤销与后台删除只挑奇数的（删除已审核的提现会连同记录一起删掉，"已审核合计"就无从核对；两类操作各自仍有同一行的重复并发），所有操作
     * 都并发地增减同一会员行的可用与冻结积分。32 个线程同时做会员中心的提现申请、撤销提现（B10），后台的审核与删除提现（B14）：
     * 没有出错，结束时每个会员的冻结积分等于他未审核提现的合计，"可用 + 冻结 + 已审核提现"等于初始的 1000（积分既不凭空产生也不丢失）。
     */
    @Test
    @Order(2)
    void concurrentMemberCashAndAdminAuditOrDeleteKeepTheLedgerBalanced() throws Exception {
        Variant v = prepare("cash-list");
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        MemberWalletService wallet = wallet(v);
        exec("DELETE FROM mac_cash");
        exec("DELETE FROM mac_user");
        StringBuilder sb = new StringBuilder("INSERT INTO mac_user (user_id, group_id, user_name, user_points, user_points_froze) VALUES ");
        for (int u = 1; u <= 10; u++) {
            sb.append(u == 1 ? "" : ",").append("(").append(u).append(", 2, 'ledger").append(u).append("', 1000, 0)");
        }
        exec(sb.toString());
        CLOCK.set(Instant.now().getEpochSecond());
        var outcome = ConcurrentWrites.run(32, i -> {
            long user = 1 + (i % 10);
            for (int round = 0; round < 6; round++) {
                switch ((i + round) % 4) {
                    case 0 -> wallet.applyCash(member(user), params("cash_money=1&cash_bank_name=b&cash_bank_no=1&cash_payee_name=p"));
                    case 1 -> {
                        String ids = idsOf("SELECT cash_id FROM mac_cash WHERE cash_id % 2 = 1 AND user_id = " + user
                                + " ORDER BY cash_id LIMIT 2");
                        if (!ids.isEmpty()) {
                            wallet.deleteCash(member(user), params("ids=" + ids));
                        }
                    }
                    case 2 -> {
                        String ids = idsOf("SELECT cash_id FROM mac_cash WHERE cash_id % 2 = 0 AND user_id = " + user
                                + " ORDER BY cash_id DESC LIMIT 2");
                        if (!ids.isEmpty()) {
                            admin.finance().audit(form("ids=" + ids));
                        }
                    }
                    default -> {
                        String ids = idsOf("SELECT cash_id FROM mac_cash WHERE cash_id % 2 = 1 AND cash_status = 0 AND user_id = "
                                + user + " LIMIT 1");
                        if (!ids.isEmpty()) {
                            admin.finance().deleteCashes(form("ids=" + ids));
                        }
                    }
                }
            }
            return true;
        });
        assertThat(outcome.failures()).isEmpty();
        assertThat(count(FROZE_RECONCILIATION)).as("冻结积分 = 未审核提现合计").isZero();
        for (int u = 1; u <= 10; u++) {
            long total = count("SELECT user_points + user_points_froze FROM mac_user WHERE user_id = ?", u)
                    + count("SELECT COALESCE(SUM(cash_points), 0) FROM mac_cash WHERE cash_status = 1 AND user_id = ?", u);
            assertThat(total).as("会员 " + u + "：可用 + 冻结 + 已审核提现").isEqualTo(1000);
        }
        assertThat(count("SELECT COUNT(*) FROM mac_cash WHERE cash_status = 1")).as("有审核发生").isPositive();
    }

    private static String idsOf(String sql) {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return String.join(",", out);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 16 个管理员同时审核同一批提现：每条只审核一次、冻结积分只扣一次（旧版每次都扣）。 */
    @Test
    @Order(3)
    void concurrentAuditsOfTheSameRowsSettleEachCashOnce() throws Exception {
        Variant v = prepare("cash-list");
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        exec("UPDATE mac_user SET user_points_froze = 20 WHERE user_id = 2");
        exec("UPDATE mac_user SET user_points_froze = 40 WHERE user_id = 1");
        exec("UPDATE mac_user SET user_points_froze = 5 WHERE user_id = 4");
        CLOCK.set(Instant.now().getEpochSecond());
        var outcome = ConcurrentWrites.run(16, i -> admin.finance().audit(form("ids=1,2,3,4,5")).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM mac_cash WHERE cash_status = 1")).isEqualTo(5);
        assertThat(count("SELECT user_points_froze FROM mac_user WHERE user_id = 2")).isZero();
        assertThat(count("SELECT user_points_froze FROM mac_user WHERE user_id = 1")).isZero();
        assertThat(count("SELECT user_points_froze FROM mac_user WHERE user_id = 4")).isZero();
        assertThat(count("SELECT user_points FROM mac_user WHERE user_id = 2")).as("可用积分不变").isEqualTo(50);
    }

    /** 12 个管理员同时删除同一条未审核提现：冻结积分只退回一次。 */
    @Test
    @Order(4)
    void concurrentDeletesOfOnePendingCashRefundOnce() throws Exception {
        Variant v = prepare("cash-list");
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        var outcome = ConcurrentWrites.run(12, i -> admin.finance().deleteCashes(form("ids=1")).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM mac_cash WHERE cash_id = 1")).isZero();
        assertThat(count("SELECT user_points FROM mac_user WHERE user_id = 2")).as("50 + 20").isEqualTo(70);
        assertThat(count("SELECT user_points_froze FROM mac_user WHERE user_id = 2")).as("30 - 20").isEqualTo(10);
    }

    /**
     * 后台保存会员表单是整行覆盖（与旧版一致，路线图 B13/B14 的同一结论）：打开表单之后会员在前台的提现冻结被表单里的积分覆盖；
     * 1 次保存与 15 次并发提现申请同时发生时没有出错，结束时可用积分落在"表单值 − 保存之后的冻结"之内，冻结积分只增不减。
     */
    @Test
    @Order(5)
    void adminSaveOverwritesPointsLikeLegacyEvenWhileTheMemberWithdraws() throws Exception {
        Variant v = prepare("user-save");
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        MemberWalletService wallet = wallet(v);
        exec("DELETE FROM mac_cash WHERE user_id = 1");
        exec("UPDATE mac_user SET user_points = 1000, user_points_froze = 0 WHERE user_id = 1");
        PhpForm opened = form("user_id=1&user_name=alice01&user_pwd=&user_status=1&group_id=2&user_points=1000");
        CLOCK.set(Instant.now().getEpochSecond());
        var outcome = ConcurrentWrites.run(16, i -> i == 0 ? (admin.users().save(opened.copy()).ok() ? "saved" : "failed")
                : Integer.toString(wallet.applyCash(member(1), params("cash_money=1&cash_bank_name=b&cash_bank_no=1&cash_payee_name=p"))
                        .code()));
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).contains("saved").doesNotContain("failed");
        long points = count("SELECT user_points FROM mac_user WHERE user_id = 1");
        long froze = count("SELECT user_points_froze FROM mac_user WHERE user_id = 1");
        assertThat(points).isBetween(1000 - froze, 1000L);
        assertThat(count("SELECT COUNT(*) FROM mac_cash WHERE user_id = 1 AND cash_status = 0") * 100).as("每次提现冻结 100 积分")
                .isEqualTo(froze);
    }

    /** 同时生成卡密：8 个管理员各生成 50 张，共 400 行，同一批里卡号互不相同。 */
    @Test
    @Order(6)
    void concurrentCardGenerationInsertsEveryBatch() throws Exception {
        Variant v = prepare("card-make");
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        long before = count("SELECT COUNT(*) FROM mac_card");
        CLOCK.set(Instant.now().getEpochSecond());
        var outcome = ConcurrentWrites.run(8, i -> admin.cards().generate(form("num=50&money=10&point=100&role_no=&role_pwd=num")).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM mac_card")).isEqualTo(before + 400);
        assertThat(count("SELECT COUNT(DISTINCT card_no) FROM mac_card WHERE card_id > 4")).isEqualTo(400);
        assertThat(count("SELECT COUNT(*) FROM mac_card WHERE card_id > 4 AND (CHAR_LENGTH(card_no) <> 16 OR card_pwd NOT REGEXP '^[0-9]{8}$')"))
                .isZero();
    }

    /**
     * 到期降级（与 B10 共用 {@code user_end_time <= now}）：到期时间恰好等于现在的会员在第 1 页被降为 2 组，第 2 页不触发；会员组删除时
     * 组下又被分配了会员（检查与删除之间），条件删除不删这一组。
     */
    @Test
    @Order(7)
    void firstPageExpiresAtTheBoundaryAndGroupDeleteKeepsGroupsThatGainedMembers() throws Exception {
        Variant v = prepare("user-list");
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        long now = 1_800_000_000L;
        CLOCK.set(now);
        exec("UPDATE mac_user SET group_id = 3, user_end_time = " + now + " WHERE user_id = 3");
        exec("UPDATE mac_user SET group_id = 3, user_end_time = " + (now + 1) + " WHERE user_id = 1");
        admin.users().list(form("page=2"), new AdminPaging(2, 2));
        assertThat(count("SELECT group_id FROM mac_user WHERE user_id = 3")).as("第 2 页不降级").isEqualTo(3);
        AdminRowPage page = admin.users().list(form(""), new AdminPaging(1, 20));
        assertThat(page.total()).isEqualTo(6);
        assertThat(count("SELECT group_id FROM mac_user WHERE user_id = 3")).as("等于现在也降级").isEqualTo(2);
        assertThat(count("SELECT group_id FROM mac_user WHERE user_id = 1")).as("还没到期").isEqualTo(3);

        // 会员组 5 没有会员：并发地一边删组、一边把会员 1 改到组 5
        var outcome = ConcurrentWrites.run(2, i -> i == 0 ? admin.groups().delete(form("ids=5")).ok()
                : admin.users().save(form("user_id=1&user_name=alice01&group_id=5")).ok());
        assertThat(outcome.failures()).isEmpty();
        long groupLeft = count("SELECT COUNT(*) FROM mac_group WHERE group_id = 5");
        long members = count("SELECT COUNT(*) FROM mac_user WHERE group_id = 5");
        if (groupLeft == 1) {
            assertThat(members).as("组保留时会员已分配进去（删除被条件挡下）").isEqualTo(1);
        }
    }

    /** 访问日志一页里所属内容超过一次查找的上限（100 个编号）时按批查名称：250 条日志、150 个不同的视频，名称全部取到。 */
    @Test
    @Order(9)
    void ulogPagesWithManyDistinctContentIdsLookUpNamesInBatches() throws Exception {
        Variant v = prepare("ulog-list");
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        exec("DELETE FROM mac_ulog");
        exec("DELETE FROM mac_vod");
        StringBuilder vods = new StringBuilder("INSERT INTO mac_vod (vod_id, vod_name, type_id, type_id_1, vod_status, vod_content, "
                + "vod_play_from, vod_play_server, vod_play_note, vod_play_url, vod_down_from, vod_down_server, vod_down_note, "
                + "vod_down_url, vod_plot_name, vod_plot_detail) VALUES ");
        StringBuilder ulogs = new StringBuilder("INSERT INTO mac_ulog (user_id, ulog_mid, ulog_type, ulog_rid, ulog_time) VALUES ");
        for (int i = 1; i <= 150; i++) {
            vods.append(i == 1 ? "" : ",").append("(").append(i).append(", '视频").append(i).append("', 2, 1, 1, '', '', '', '', '', '', '', '', '', '', '')");
        }
        for (int i = 1; i <= 250; i++) {
            ulogs.append(i == 1 ? "" : ",").append("(1, 1, 2, ").append(1 + (i - 1) % 150).append(", 1700000000)");
        }
        exec(vods.toString());
        exec(ulogs.toString());
        AdminRowPage page = admin.logs().ulogs(form(""), new AdminPaging(1, 1000));
        assertThat(page.rows()).hasSize(250);
        assertThat(page.rows()).allSatisfy(r -> assertThat(r.s("data_name")).isEqualTo("视频" + r.s("ulog_rid")));
    }

    // ------------------------------------------------------------------ ②' 同一条提现的审核与删除/撤销交错（屏障控制，23 文档第 6.2 节）

    /**
     * 屏障：被包装的 Mapper 第一次调用 {@code method} 时，在执行该语句之前停下（{@link #reached}），等测试 {@link #release()} 后再执行。
     * 用来把"认领之后、扣冻结之前"等窗口固定下来，逐步断言库里的状态；不靠随机多跑。
     */
    static final class Pause {
        final String method;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean(true);

        Pause(String method) {
            this.method = method;
        }

        void awaitReached() throws InterruptedException {
            assertThat(reached.await(30, TimeUnit.SECONDS)).as("到达屏障 " + method).isTrue();
        }

        void release() {
            released.countDown();
        }
    }

    static <T> T pausing(Class<T> type, T target, Pause... pauses) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, m, args) -> {
            for (Pause p : pauses) {
                if (m.getName().equals(p.method) && p.armed.compareAndSet(true, false)) {
                    p.reached.countDown();
                    if (!p.released.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("屏障超时：" + p.method);
                    }
                }
            }
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }));
    }

    /** 与容器里的提现服务相同（同一可写数据源、同样的语句），只是写入经带屏障的 Mapper。 */
    private static FinanceAdminService pausedFinance(Variant v, Pause... pauses) {
        WriteDatabase db = v.app().getBean(WriteDatabase.class);
        MemberAdminWriteMapper adminWrites = pausing(MemberAdminWriteMapper.class, db.mapper(MemberAdminWriteMapper.class), pauses);
        MemberFinanceWriteMapper financeWrites = pausing(MemberFinanceWriteMapper.class, db.mapper(MemberFinanceWriteMapper.class),
                pauses);
        return new FinanceAdminService(v.app().getBean(MemberAdminMapper.class), new MemberAdminWrites(adminWrites, financeWrites),
                CLOCK);
    }

    /** 会员 50：可用 100、冻结 {@code froze}；提现 50：会员 50、未审核、20 积分。其余提现不参与。 */
    private static Variant cashRace(long froze) throws Exception {
        Variant v = prepare("cash-list");
        exec("DELETE FROM mac_cash");
        exec("INSERT INTO mac_user (user_id, group_id, user_name, user_points, user_points_froze) VALUES (50, 2, 'race50', 100, "
                + froze + ")");
        exec("INSERT INTO mac_cash (cash_id, user_id, cash_status, cash_points, cash_money, cash_bank_name, cash_bank_no, "
                + "cash_payee_name, cash_time, cash_time_audit) VALUES (50, 50, 0, 20, '0.20', 'b', '1', 'p', 1700000000, 0)");
        exec("UPDATE mac_user SET user_points_froze = 0 WHERE user_id <> 50");
        CLOCK.set(1_800_000_000L);
        return v;
    }

    private static long points50() throws SQLException {
        return count("SELECT user_points FROM mac_user WHERE user_id = 50");
    }

    private static long froze50() throws SQLException {
        return count("SELECT user_points_froze FROM mac_user WHERE user_id = 50");
    }

    /** 提现 50 的一列；这一行不存在时为 -1。 */
    private static long cash50(String col) throws SQLException {
        return count("SELECT COALESCE(MAX(" + col + "), -1) FROM mac_cash WHERE cash_id = 50");
    }

    private static String text(AdminResult r) {
        return r.message().render(k -> k);
    }

    /**
     * 成功分支：审核认领了提现 50（{@code cash_status=1}、写了审核时间），还没扣冻结时，同一行被后台删除——删除按"已审核"只删记录、不退冻结；
     * 放行后扣冻结命中。冻结只扣一次、没有退回，冻结积分与未审核合计一致。
     */
    @Test
    @Order(10)
    void auditClaimedThenAdminDeleteOfTheSameCashSettlesOnceWithoutRefund() throws Exception {
        Variant v = cashRace(20);
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        Pause beforeSettle = new Pause("settleFrozen");
        FinanceAdminService auditor = pausedFinance(v, beforeSettle);
        CompletableFuture<AdminResult> audit = CompletableFuture.supplyAsync(() -> auditor.audit(form("ids=50")));
        beforeSettle.awaitReached();
        assertThat(cash50("cash_status")).as("已认领").isEqualTo(1);
        assertThat(cash50("cash_time_audit")).isEqualTo(1_800_000_000L);
        assertThat(froze50()).as("还没扣冻结").isEqualTo(20);

        AdminResult del = admin.finance().deleteCashes(form("ids=50"));
        assertThat(del.ok()).isTrue();
        assertThat(cash50("cash_id")).as("已审核的记录被删除").isEqualTo(-1);
        assertThat(points50()).as("按已审核删除，不退冻结").isEqualTo(100);
        assertThat(froze50()).isEqualTo(20);

        beforeSettle.release();
        AdminResult a = audit.get(30, TimeUnit.SECONDS);
        assertThat(a.ok()).isTrue();
        assertThat(text(a)).isEqualTo("审核成功");
        assertThat(froze50()).as("冻结只扣一次").isZero();
        assertThat(points50()).as("没有退回").isEqualTo(100);
        assertThat(count(FROZE_RECONCILIATION)).as("冻结积分 = 未审核提现合计").isZero();
    }

    /** 同上，删除换成会员中心的撤销提现（B10）：撤销按"已审核"只删记录、不退冻结，放行后扣冻结命中一次。 */
    @Test
    @Order(11)
    void auditClaimedThenMemberRevokeOfTheSameCashSettlesOnceWithoutRefund() throws Exception {
        Variant v = cashRace(20);
        MemberWalletService wallet = wallet(v);
        Pause beforeSettle = new Pause("settleFrozen");
        FinanceAdminService auditor = pausedFinance(v, beforeSettle);
        CompletableFuture<AdminResult> audit = CompletableFuture.supplyAsync(() -> auditor.audit(form("ids=50")));
        beforeSettle.awaitReached();
        assertThat(cash50("cash_status")).isEqualTo(1);

        JsonReply revoke = wallet.deleteCash(member(50), params("ids=50"));
        assertThat(revoke.code()).isEqualTo(1);
        assertThat(cash50("cash_id")).isEqualTo(-1);
        assertThat(points50()).isEqualTo(100);
        assertThat(froze50()).isEqualTo(20);

        beforeSettle.release();
        assertThat(audit.get(30, TimeUnit.SECONDS).ok()).isTrue();
        assertThat(froze50()).isZero();
        assertThat(points50()).isEqualTo(100);
        assertThat(count(FROZE_RECONCILIATION)).isZero();
    }

    /**
     * 删除先认领：审核读到未审核、去认领之前停下；后台删除认领（删掉这一行）、退冻结之前停下；放行审核——认领不命中，跳过；放行删除——退冻结命中。
     * 只退一次、没有扣减。
     */
    @Test
    @Order(12)
    void deleteClaimedFirstThenAuditSkipsAndTheRefundHappensOnce() throws Exception {
        Variant v = cashRace(20);
        Pause beforeUnfreeze = new Pause("unfreezePoints");
        Pause beforeClaim = new Pause("auditCash");
        FinanceAdminService deleter = pausedFinance(v, beforeUnfreeze);
        FinanceAdminService auditor = pausedFinance(v, beforeClaim);
        CompletableFuture<AdminResult> audit = CompletableFuture.supplyAsync(() -> auditor.audit(form("ids=50")));
        beforeClaim.awaitReached();
        assertThat(cash50("cash_status")).as("审核已读到未审核，尚未认领").isZero();
        CompletableFuture<AdminResult> del = CompletableFuture.supplyAsync(() -> deleter.deleteCashes(form("ids=50")));
        beforeUnfreeze.awaitReached();
        assertThat(cash50("cash_id")).as("删除已认领").isEqualTo(-1);
        assertThat(froze50()).isEqualTo(20);

        beforeClaim.release();
        AdminResult a = audit.get(30, TimeUnit.SECONDS);
        assertThat(a.ok()).as("认领不命中：跳过").isTrue();
        assertThat(froze50()).as("审核没有扣").isEqualTo(20);
        assertThat(points50()).isEqualTo(100);

        beforeUnfreeze.release();
        assertThat(del.get(30, TimeUnit.SECONDS).ok()).isTrue();
        assertThat(points50()).as("退回一次").isEqualTo(120);
        assertThat(froze50()).isZero();
        assertThat(count(FROZE_RECONCILIATION)).isZero();
    }

    /**
     * 补偿分支（扣冻结不命中）：会员的冻结积分已不够（数据不一致，如后台表单改过冻结积分；旧版此时扣成负数报 500），审核认领后扣冻结不命中、
     * 撤回审核之前，同一行被后台删除（按已审核只删记录）。放行后撤回审核不命中：审核返回"更新用户积分失败："，并记 ERROR 日志（提现号、会员号、
     * 积分）。没有重复退或扣：可用、冻结都没变；对账语句列出这位会员（冻结 19、未审核 0），可人工核对处理。
     */
    @Test
    @Order(13)
    void settleMissThenConcurrentDeleteIsReportedForManualReconciliationWithoutDoubleMoves() throws Exception {
        Variant v = cashRace(19);
        MemberAdminServices admin = v.app().getBean(MemberAdminServices.class);
        Pause beforeRevert = new Pause("revertAudit");
        FinanceAdminService auditor = pausedFinance(v, beforeRevert);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FinanceAdminService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            CompletableFuture<AdminResult> audit = CompletableFuture.supplyAsync(() -> auditor.audit(form("ids=50")));
            beforeRevert.awaitReached();
            assertThat(cash50("cash_status")).as("已认领，扣冻结没有命中").isEqualTo(1);
            assertThat(froze50()).isEqualTo(19);

            assertThat(admin.finance().deleteCashes(form("ids=50")).ok()).isTrue();
            assertThat(cash50("cash_id")).isEqualTo(-1);
            assertThat(points50()).isEqualTo(100);
            assertThat(froze50()).isEqualTo(19);

            beforeRevert.release();
            AdminResult a = audit.get(30, TimeUnit.SECONDS);
            assertThat(a.ok()).isFalse();
            assertThat(text(a)).isEqualTo("更新用户积分失败：");
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(points50()).as("没有退回").isEqualTo(100);
        assertThat(froze50()).as("没有扣减").isEqualTo(19);
        assertThat(count(FROZE_RECONCILIATION)).as("对账语句列出这位会员").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM (SELECT u.user_id FROM mac_user AS u LEFT JOIN mac_cash AS c ON c.user_id = u.user_id "
                + "AND c.cash_status = 0 WHERE u.user_id = 50 GROUP BY u.user_id, u.user_points_froze "
                + "HAVING u.user_points_froze = 19 AND COALESCE(SUM(c.cash_points), 0) = 0) AS x")).isEqualTo(1);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("cash_id=50").contains("user_id=50").contains("points=20");
        });
    }

    /**
     * 补偿分支（撤回成功）：冻结积分不够，扣冻结不命中，撤回审核命中——提现回到未审核、审核时间复原，审核返回"更新用户积分失败："；之后同一行的
     * 后台删除认领成功、退冻结同样不命中，插回之前停下（此时再审核读不到这一行，什么都不做），放行后原样插回。可用、冻结、提现行都与开始时相同。
     */
    @Test
    @Order(14)
    void settleMissWithSuccessfulCompensationThenDeleteRestoresEverything() throws Exception {
        Variant v = cashRace(19);
        Pause beforeRevert = new Pause("revertAudit");
        Pause beforeRestore = new Pause("restoreCash");
        FinanceAdminService auditor = pausedFinance(v, beforeRevert);
        FinanceAdminService deleter = pausedFinance(v, beforeRestore);
        CompletableFuture<AdminResult> audit = CompletableFuture.supplyAsync(() -> auditor.audit(form("ids=50")));
        beforeRevert.awaitReached();
        assertThat(cash50("cash_status")).isEqualTo(1);
        assertThat(froze50()).isEqualTo(19);
        beforeRevert.release();
        AdminResult a = audit.get(30, TimeUnit.SECONDS);
        assertThat(text(a)).isEqualTo("更新用户积分失败：");
        assertThat(cash50("cash_status")).as("撤回审核").isZero();
        assertThat(cash50("cash_time_audit")).isZero();

        CompletableFuture<AdminResult> del = CompletableFuture.supplyAsync(() -> deleter.deleteCashes(form("ids=50")));
        beforeRestore.awaitReached();
        assertThat(cash50("cash_id")).as("删除已认领，退冻结不命中，插回之前").isEqualTo(-1);
        assertThat(v.app().getBean(MemberAdminServices.class).finance().audit(form("ids=50")).ok()).isTrue();
        assertThat(froze50()).isEqualTo(19);
        beforeRestore.release();
        assertThat(text(del.get(30, TimeUnit.SECONDS))).isEqualTo("更新用户积分失败：");
        assertThat(cash50("cash_status")).as("原样插回").isZero();
        assertThat(cash50("cash_points")).isEqualTo(20);
        assertThat(cash50("cash_time")).isEqualTo(1_700_000_000L);
        assertThat(points50()).isEqualTo(100);
        assertThat(froze50()).isEqualTo(19);
    }

    // ------------------------------------------------------------------ ③ 回滚演练

    /**
     * 快照（8 张会员与财务表）→ 经各服务做一遍本批的写入（会员新增/编辑/状态/删除与到期降级、会员组增改删与状态、生成与删除卡密、删除订单、
     * 提现审核与删除、积分日志/访问日志/推广访问的删除与清空）→ {@code DmlRestore} 只用 DML 恢复 → 与快照逐行 diff 为空 → 表结构指纹不变。
     */
    @Test
    @Order(8)
    void rollbackDrillRestoresEveryB14WriteWithDmlOnly() throws Exception {
        Variant v = prepare("user-list");
        MemberAdminServices a = v.app().getBean(MemberAdminServices.class);
        List<String> tables = List.of("mac_user", "mac_group", "mac_card", "mac_order", "mac_cash", "mac_plog", "mac_ulog", "mac_visit");
        CLOCK.set(Instant.now().getEpochSecond());
        DatabaseSnapshot before;
        try (Connection c = admin(dbName)) {
            before = DatabaseSnapshot.take(c, tables);
        }
        a.users().list(form(""), new AdminPaging(1, 20));
        assertThat(a.users().save(form("user_id=&user_name=drilluser&user_pwd=x&group_id=2")).ok()).isTrue();
        assertThat(a.users().save(form("user_id=1&user_name=alice01&user_pwd=new&user_points=1&user_points_froze=2")).ok()).isTrue();
        assertThat(a.users().field(form("ids=1,2&col=user_status&val=0")).ok()).isTrue();
        assertThat(a.users().delete(form("ids=5")).ok()).isTrue();
        assertThat(a.groups().save(form("group_id=&group_name=drill"), null).ok()).isTrue();
        assertThat(a.groups().save(form("group_id=3&group_name=vip2&group_type%5B%5D=1"), Map.of("1", Map.of("1", "1".getBytes()))).ok())
                .isTrue();
        assertThat(a.groups().field(form("ids=3&col=group_status&val=0")).ok()).isTrue();
        assertThat(a.groups().delete(form("ids=5")).ok()).isTrue();
        assertThat(a.cards().generate(form("num=5&money=1&point=1")).ok()).isTrue();
        assertThat(a.cards().delete(form("ids=2")).ok()).isTrue();
        assertThat(a.finance().deleteOrders(form("ids=1")).ok()).isTrue();
        assertThat(a.finance().audit(form("ids=1")).ok()).isTrue();
        assertThat(a.finance().deleteCashes(form("ids=2,3")).ok()).isTrue();
        assertThat(a.logs().deletePlogs(form("ids=1")).ok()).isTrue();
        assertThat(a.logs().deleteUlogs(form("ids=1&all=1")).ok()).isTrue();
        assertThat(a.logs().deleteVisits(form("ids=2")).ok()).isTrue();
        try (Connection c = admin(dbName)) {
            DatabaseSnapshot changed = DatabaseSnapshot.take(c, tables);
            assertThat(SnapshotDiff.between(before, changed).isEmpty()).isFalse();
            List<DmlRestore.Statement> plan = DmlRestore.plan(before, changed);
            assertThat(plan).isNotEmpty().allMatch(x -> x.sql().startsWith("INSERT") || x.sql().startsWith("UPDATE")
                    || x.sql().startsWith("DELETE"));
            DmlRestore.apply(c, plan);
            SnapshotDiff diff = SnapshotDiff.between(before, DatabaseSnapshot.take(c, tables));
            assertThat(diff.isEmpty()).as(diff.summary()).isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).isEmpty();
            System.out.println("[mysql-it] B14 rollback drill restored with " + plan.size() + " DML statements");
        }
    }

    // ------------------------------------------------------------------ 表

    /** 清空跟踪的表、复位自增计数（一次性库上的测试夹具，与黄金脚本相同），写入场景的行与两位管理员。 */
    private static void seed(JsonNode seed) throws SQLException {
        try (Connection c = admin(dbName)) {
            try (Statement st = c.createStatement()) {
                for (String t : TABLES.keySet()) {
                    st.execute("DELETE FROM " + t);
                    st.execute("ALTER TABLE " + t + " AUTO_INCREMENT = 1");
                }
                st.execute("DELETE FROM mac_admin");
                st.execute("ALTER TABLE mac_admin AUTO_INCREMENT = 1");
                st.execute("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
                        + "(1, 'admin', '" + md5("admin888") + "', 1, ''), "
                        + "(2, 'editor', '" + md5("ed pass") + "', 1, ',user/data,user/info,group/index,')");
            }
            Iterator<Map.Entry<String, JsonNode>> it = seed.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String table = e.getKey();
                for (JsonNode row : e.getValue()) {
                    List<String> cols = new ArrayList<>();
                    row.properties().forEach(x -> cols.add(x.getKey()));
                    String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < cols.size(); i++) {
                            String kind = columnKinds.get(table).get(cols.get(i));
                            JsonNode val = row.get(cols.get(i));
                            if ("text".equals(kind)) {
                                ps.setString(i + 1, new String(HexFormat.of().parseHex(val.asString()), StandardCharsets.UTF_8));
                            } else {
                                ps.setString(i + 1, val.asString());
                            }
                        }
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    static Map<String, List<Map<String, Object>>> javaRows() throws SQLException {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> t : TABLES.entrySet()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + t.getKey() + " ORDER BY " + t.getValue())) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String col = md.getColumnLabel(i);
                            String kind = columnKinds.get(t.getKey()).get(col);
                            if ("int".equals(kind)) {
                                row.put(col, rs.getLong(i));
                            } else if ("decimal".equals(kind)) {
                                row.put(col, rs.getString(i));
                            } else {
                                byte[] b = rs.getBytes(i);
                                row.put(col, HexFormat.of().formatHex(b == null ? new byte[0] : b));
                            }
                        }
                        rows.add(row);
                    }
                }
                out.put(t.getKey(), rows);
            }
        }
        return out;
    }

    static Map<String, List<Map<String, Object>>> rows(JsonNode tables) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (String t : TABLES.keySet()) {
            out.put(t, tableRows(tables.get(t)));
        }
        return out;
    }

    private static List<Map<String, Object>> tableRows(JsonNode rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (JsonNode r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            r.properties().forEach(e -> row.put(e.getKey(), e.getValue().isNumber() ? (Object) e.getValue().asLong()
                    : e.getValue().asString()));
            out.add(row);
        }
        return out;
    }

    private static Map<String, List<Map<String, Object>>> merge(Map<String, List<Map<String, Object>>> last, JsonNode changed) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>(last);
        if (changed != null) {
            changed.properties().forEach(e -> out.put(e.getKey(), tableRows(e.getValue())));
        }
        return out;
    }

    static List<String> diffRows(List<Map<String, Object>> php, List<Map<String, Object>> java, long started, long after,
                                 Set<String> accepted) {
        return AdminContentGoldenMySqlIT.diffRows(php, java, started, after, accepted);
    }

    // ------------------------------------------------------------------ 工具

    static JsonNode load(String resource) {
        try (InputStream in = AdminMemberGoldenMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Map<String, String>> columnKinds(Connection c) throws SQLException {
        Map<String, Map<String, String>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE()"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String type = rs.getString(3);
                String kind = List.of("tinyint", "smallint", "mediumint", "int", "bigint").contains(type) ? "int"
                        : "decimal".equals(type) ? "decimal" : "text";
                out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), kind);
            }
        }
        return out;
    }

    static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return v;
    }

    static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(database == null ? adminUrl : jdbcUrlFor(database), adminUser, adminPassword);
    }

    private static String jdbcUrlFor(String database) {
        int q = adminUrl.indexOf('?');
        String base = q < 0 ? adminUrl : adminUrl.substring(0, q);
        String query = q < 0 ? "" : adminUrl.substring(q);
        return (base.endsWith("/") ? base : base + "/") + database + query;
    }

    private static List<String> installStatements() throws Exception {
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(INSTALL_SQL, StandardCharsets.UTF_8)) {
            if (!line.startsWith("--")) {
                kept.add(line);
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : String.join("\n", kept).split(";\\s*\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }
}

package com.maccms.backend.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * 会员登录态的真实 MySQL 对照（B08，./mvnw verify -Pmysql-it；17 文档第 3、4、6 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 原样导入 install.sql（自带 1/2/3 三个会员组）→ 写入 PHP 黄金
 * 中同样的合成会员 → 数据快照 S0 → 用应用的只读 {@link MemberMapper} 逐条复放 PHP 8.0/7.3 黄金的 label_user 用例、到期边界、
 * PHP 真实登录签发的 cookie（PHP → Java）与新版签发的 cookie → 每段之后核对 {@code mac_user}/{@code mac_group} 与 S0 逐行相同
 * （B08 不写库）→ 删库并确认不存在。
 *
 * <p>与 PHP 结果不同的用例只有 17 文档第 5 节登记的有意差异：库内 {@code user_random} 为空时伪造的 cookie（D-B08-2）、
 * {@code 0e…} 宽松比较（D-B08-1）、PHP 8 对数组 cookie 抛类型错误（新版按游客）、PHP 7.3 把 cookie 中的 {@code +} 解成空格。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemberGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final List<String> TABLES = List.of("mac_user", "mac_group");

    /** PHP 认、新版不认的用例（有意差异）。 */
    private static final Set<String> JAVA_GUEST = Set.of("valid-10", "neverlogin-forged", "magic-0e0", "magic-00",
            "magic-0e999", "magic-0.0");
    /** PHP 7.3 把 cookie 中的 + 解成空格后再 trim：md5 后接 + 的值在 7.3 下被接受，新版按 8.0。 */
    private static final Set<String> PHP73_ONLY = Set.of("check-plus");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static DatabaseSnapshot s0;
    private static ConfigurableApplicationContext app;
    private static MemberMapper mapper;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + String.format("%02x%02x%02x%02x", rnd[0], rnd[1], rnd[2], rnd[3]);
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
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO mac_user (user_id, group_id, user_name, "
                    + "user_pwd, user_status, user_random, user_end_time) VALUES (?, ?, ?, MD5(?), ?, ?, 0)")) {
                for (JsonNode u : MemberGolden.load(MemberGolden.PHP80).get("users")) {
                    ps.setLong(1, u.get("user_id").asLong());
                    ps.setInt(2, u.get("group_id").asInt());
                    ps.setString(3, MemberGolden.utf8(u.get("user_name")));
                    ps.setString(4, "b08-pass");
                    ps.setInt(5, u.get("user_status").asInt());
                    ps.setString(6, u.get("user_random").asString());
                    ps.executeUpdate();
                }
            }
            s0 = DatabaseSnapshot.take(c, TABLES);
        }
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.NONE)
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
        }
        mapper = app.getBean(MemberMapper.class);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            try (Connection c = admin(null); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE `" + dbName + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name = '" + dbName + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                }
            }
            System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
        }
    }

    /** ① 黄金 label_user 用例（不含到期边界）：PHP 8.0 与 7.3 各自逐条对照。 */
    @Test
    @Order(1)
    void labelUserCasesMatchPhp() throws Exception {
        MemberSessionService service = new MemberSessionService(mapper, Clock.systemUTC());
        for (String version : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            int compared = 0;
            for (JsonNode c : MemberGolden.load(version).get("label_user")) {
                if (c.has("end_offset")) {
                    continue;
                }
                String label = c.get("label").asString();
                MemberState java = service.resolve(PhpCookies.parse(MemberGolden.latin1(c.get("header"))));
                String what = version + " " + label;
                if (c.has("error")) {
                    // PHP 8 对数组 cookie 抛 TypeError（500）；新版与 7.3 相同按游客
                    assertThat(java.member()).as(what).isFalse();
                } else if (JAVA_GUEST.contains(label)
                        || (version.equals(MemberGolden.PHP73) && PHP73_ONLY.contains(label))) {
                    assertThat(c.get("member").asBoolean()).as(what + "（PHP 认）").isTrue();
                    assertThat(java.member()).as(what + "（有意差异）").isFalse();
                } else {
                    assertThat(java.member()).as(what).isEqualTo(c.get("member").asBoolean());
                    if (java.member()) {
                        assertThat(java.userId()).as(what).isEqualTo(c.get("user_id").asLong());
                        assertThat(java.userName()).as(what).isEqualTo(MemberGolden.utf8(c.get("user_name")));
                        assertThat(java.groupId()).as(what).isEqualTo(c.get("group_group_id").asInt());
                        assertThat(java.group()).isNotNull();
                    } else {
                        assertThat(java.groupId()).isEqualTo(MemberState.GUEST_GROUP_ID);
                        assertThat(java.group().id()).isEqualTo(1);
                    }
                }
                compared++;
            }
            assertThat(compared).as(version).isGreaterThanOrEqualTo(65);
        }
        assertUnchanged("label_user");
    }

    /**
     * ② 到期边界：{@code user_end_time = 现在 + offset}。checkLogin 用 {@code <}：offset=0（等于现在）不到期，−1 到期；到期只在内存中
     * 按 2 组。PHP 黄金在同一秒内设置并检查（same_second）。
     */
    @Test
    @Order(2)
    void expiryBoundaryMatchesPhp() throws Exception {
        long now = Instant.now().getEpochSecond();
        MemberSessionService service = new MemberSessionService(mapper, Clock.fixed(Instant.ofEpochSecond(now),
                ZoneOffset.UTC));
        for (String version : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            int compared = 0;
            for (JsonNode c : MemberGolden.load(version).get("label_user")) {
                if (!c.has("end_offset")) {
                    continue;
                }
                assertThat(c.get("same_second").asBoolean()).isTrue();
                long id = c.get("row_after").isNull() ? 0 : Long.parseLong(c.get("label").asString().split("-")[1]);
                int group = c.get("group_before").asInt();
                long offset = c.get("end_offset").asLong();
                setRow(id, group, now + offset, null);
                try {
                    MemberState java = service.resolve(PhpCookies.parse(MemberGolden.latin1(c.get("header"))));
                    String what = version + " " + c.get("label").asString();
                    assertThat(java.member()).as(what).isEqualTo(c.get("member").asBoolean()).isTrue();
                    assertThat(java.storedGroupId()).as(what).isEqualTo(c.get("info_group_id").asInt());
                    JsonNode phpGroup = c.get("group_group_id");
                    if (phpGroup.isNull()) {
                        // 库里没有这个会员组（group 9 未到期）：旧版 $group_list[9] 取不到
                        assertThat(java.groupId()).as(what).isEqualTo(group);
                        assertThat(java.group()).as(what).isNull();
                    } else {
                        assertThat(java.groupId()).as(what).isEqualTo(phpGroup.asInt());
                        assertThat(java.group().id()).isEqualTo(phpGroup.asInt());
                    }
                    assertThat(java.expiredInMemory()).as(what).isEqualTo(group > 2 && offset < 0);
                    // PHP 到期时会写库（row_after.group_id=2）；新版只在内存中判定
                    assertThat(currentGroup(id)).as(what + " 未写库").isEqualTo(group);
                } finally {
                    restore(id);
                }
                compared++;
            }
            assertThat(compared).isEqualTo(7);
        }
        assertUnchanged("expiry");
    }

    /** ③ PHP → Java：PHP 真实登录签发、原样回送的 cookie，新版的判定与 PHP 自己的判定相同。 */
    @Test
    @Order(3)
    void phpIssuedLoginCookiesAreAcceptedLikePhpDoes() throws Exception {
        MemberSessionService service = new MemberSessionService(mapper, Clock.systemUTC());
        for (String version : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            int compared = 0;
            for (JsonNode c : MemberGolden.load(version).get("login")) {
                if (c.get("code").asInt() != 1) {
                    continue;
                }
                Map<String, PhpCookies.Value> cookies = PhpCookies.parse(MemberGolden.latin1(c.get("replay_header")));
                long id = Long.parseLong(cookies.get("user_id").text());
                if (id != c.get("user_id").asLong()) {
                    continue;
                }
                JsonNode row = c.get("row_after");
                setRow(id, row.get("group_id").asInt(), 0, row.get("user_random").asString());
                try {
                    MemberState java = service.resolve(cookies);
                    JsonNode replay = c.get("replay");
                    assertThat(java.member()).as(version + " login " + id).isEqualTo(replay.get("member").asBoolean());
                    if (java.member()) {
                        assertThat(java.userId()).isEqualTo(id);
                        assertThat(java.groupId()).isEqualTo(replay.get("group_group_id").asInt());
                    }
                } finally {
                    restore(id);
                }
                compared++;
            }
            assertThat(compared).as(version).isGreaterThanOrEqualTo(8);
        }
        assertUnchanged("login replay");
    }

    /** ④ 新版签发的 cookie（java-issued.json，PHP 两版都认）在新版中同样被认；并发校验只读、结果一致。 */
    @Test
    @Order(4)
    void javaIssuedCookiesAndConcurrentResolution() throws Exception {
        MemberSessionService service = new MemberSessionService(mapper, Clock.systemUTC());
        JsonNode issued = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(Files.readString(Path.of("src", "test", "resources", "member", "java-issued.json")));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int round = 0; round < 8; round++) {
                for (JsonNode e : issued) {
                    results.add(pool.submit(() -> {
                        MemberState s = service.resolve(PhpCookies.parse(MemberGolden.latin1(e.get("header"))));
                        boolean expected = e.get("user_id").asInt() != 3;
                        return s.member() == expected && (!expected || s.userId() == e.get("user_id").asLong());
                    }));
                }
            }
            for (Future<Boolean> f : results) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertUnchanged("java issued");
    }

    // ------------------------------------------------------------------ 辅助

    private static void assertUnchanged(String phase) throws SQLException {
        try (Connection c = admin(dbName)) {
            SnapshotDiff diff = SnapshotDiff.between(s0, DatabaseSnapshot.take(c, TABLES));
            assertThat(diff.isEmpty()).as(phase + "：mac_user/mac_group 与 S0 相同 " + diff.summary()).isTrue();
        }
    }

    /** 测试准备：只在一次性库上改一行（新版代码不写库，见 assertUnchanged）。 */
    private static void setRow(long id, int group, long endTime, String random) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(random == null
                ? "UPDATE mac_user SET group_id = ?, user_end_time = ? WHERE user_id = ?"
                : "UPDATE mac_user SET group_id = ?, user_end_time = ?, user_random = ? WHERE user_id = ?")) {
            ps.setInt(1, group);
            ps.setLong(2, endTime);
            if (random == null) {
                ps.setLong(3, id);
            } else {
                ps.setString(3, random);
                ps.setLong(4, id);
            }
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static void restore(long id) throws SQLException {
        for (JsonNode u : MemberGolden.load(MemberGolden.PHP80).get("users")) {
            if (u.get("user_id").asLong() == id) {
                setRow(id, u.get("group_id").asInt(), 0, u.get("user_random").asString());
                return;
            }
        }
        fail("no user " + id);
    }

    private static int currentGroup(long id) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_id FROM mac_user WHERE user_id = " + id)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("缺少环境变量 " + name + "：MySQL 集成测试需要外部提供管理账号，见 backend/README.md");
        }
        return v;
    }

    private static String jdbcUrlFor(String db) {
        int q = adminUrl.indexOf('?');
        String params = q >= 0 ? adminUrl.substring(q) : "";
        String path = q >= 0 ? adminUrl.substring(0, q) : adminUrl;
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path + (db == null ? "" : db) + (params.isEmpty() ? "?characterEncoding=UTF-8" : params);
    }

    private static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(db), adminUser, adminPassword);
    }

    private static List<String> installStatements() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(INSTALL_SQL, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (!line.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : sb.toString().split(";\\s*\\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }
}

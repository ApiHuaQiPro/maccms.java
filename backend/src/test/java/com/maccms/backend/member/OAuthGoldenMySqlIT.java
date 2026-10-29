package com.maccms.backend.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.session.SessionStore;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.OAuthLogin;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.oauth.OAuthConfiguration;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberOAuthService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberSettings;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第三方登录的真实 MySQL 对照（B26，./mvnw verify -Pmysql-it；34 文档第 6 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql → 快照 S0 → 以可写数据源打开的应用上下文
 * （正式登记表）取会员的只读/写 Mapper →
 * ① 逐条复放 PHP 7.3 与 8.0 的黄金（{@code integration/b26/php{73,80}-golden.json}，各 22 个用例，由 {@code src/test/php/oauth_golden.php}
 * 生成：真实的旧 {@code logincallback}，缺失的 ThinkOauth 用替身）：每个用例清空 mac_user 并写入同样的种子，新版走完整流程——发起授权取得
 * {@code state} → 本机模拟服务扮演 QQ/微信（真实的协议实现经出站客户端换令牌、openid、用户信息）→ 回调——比较返回与 mac_user 每一行；
 * 有意差异（自动注册的密码，D-B26-1）另行断言；
 * ② 安全用例：伪造、重放、无会话的 {@code state} 在出站之前拒绝且库不变；普通登录入口凭 openid/col 不能登录；自动注册账号的密码不是
 * {@code time()}；同一 openid 的并发回调只注册一个账号；
 * ③ 只用 DML 恢复到 S0、逐行 diff 为空、表结构指纹不变 → 删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OAuthGoldenMySqlIT {

    private static final java.util.regex.Pattern DB_NAME = java.util.regex.Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final Path MACCMS_PHP = Path.of("..", "application", "extra", "maccms.php");
    private static final List<String> TABLES = List.of("mac_user");
    private static final List<String> GOLDENS = List.of("php73-golden.json", "php80-golden.json");
    private static final String CLIENT_IP = "127.0.0.1";
    private static final String SITE = "http://www.example.test/";
    private static final String QQ_ID = "101000001";
    private static final String WX_ID = "wx0000000000000001";
    private static final Set<String> TIME_COLUMNS = Set.of("user_reg_time", "user_login_time", "user_last_login_time");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static DatabaseSnapshot s0;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static MemberMapper mapper;
    private static MemberWriteMapper writer;
    private static MemberSessionService sessions;
    private static PhpArray repoConfig;

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
            s0 = DatabaseSnapshot.take(c, TABLES);
        }
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + jdbcUrlFor(dbName),
                "--spring.datasource.username=" + adminUser, "--maccms.write.enabled=true",
                "--maccms.write.url=" + jdbcUrlFor(dbName), "--maccms.write.username=" + adminUser));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
            args.add("--maccms.write.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        System.setProperty("maccms.write.password", adminPassword);
        try {
            app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.NONE)
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
        mapper = app.getBean(MemberMapper.class);
        writer = app.getBean(MemberWriteMapper.class);
        sessions = app.getBean(MemberSessionService.class);
        repoConfig = VarExportParser.parse(Files.readAllBytes(MACCMS_PHP));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            Map<String, String> schemaNow = null;
            if (schemaAtImport != null) {
                try (Connection c = admin(dbName)) {
                    schemaNow = SchemaFingerprint.of(c, dbName);
                }
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
            if (schemaNow != null) {
                assertThat(SchemaFingerprint.diff(schemaAtImport, schemaNow)).as("表结构与导入 install.sql 时一致").isEmpty();
            }
        }
    }

    // ------------------------------------------------------------------ ① 黄金复放

    @Test
    @Order(1)
    void callbacksMatchPhpGolden() throws Exception {
        try (LoopbackMockServer mock = LoopbackMockServer.start()) {
            for (String file : GOLDENS) {
                JsonNode golden = load(file);
                int compared = 0;
                for (JsonNode c : golden.get("cases")) {
                    assertThat(c.has("setupError")).as(file + " " + c.get("label").asString() + " PHP 侧准备成功").isFalse();
                    replay(file + " " + c.get("label").asString(), c, mock);
                    compared++;
                }
                assertThat(compared).as(file).isEqualTo(22);
            }
        }
        assertThat(TestOutboundGuard.drainViolations()).as("只访问回环地址").isEmpty();
    }

    private void replay(String what, JsonNode c, LoopbackMockServer mock) throws Exception {
        // 生成黄金时按"现在"写入的种子时间（IP 限额用例）随复放时刻平移
        long seedNow = Instant.now().getEpochSecond();
        seed(c.get("seedUsers"), c.get("now").asLong(), seedNow);
        String type = c.get("type").asString();
        JsonNode plan = c.get("oauth");
        stub(mock, type, plan);
        Flow flow = flow(c.get("configOverrides"), mock);
        int as = c.get("as").asInt();
        MemberState member = as == 0 ? MemberState.guest(null) : memberState(c.get("seedUsers"), as);
        SessionState session = SessionStore.withDefaults(Clock.systemUTC()).create();
        String state = stateOf(flow.oauth().start(type, member, session));
        long javaNow = seedNow;
        MemberOAuthService.Callback r = flow.oauth().callback(type, c.get("code").asString().getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.US_ASCII), member, session, CLIENT_IP);
        // 返回
        if (c.has("response") && c.get("response").get("status").asInt() == 302) {
            assertThat(r.kind()).as(what).isEqualTo(MemberOAuthService.Callback.Kind.LOGGED_IN);
            List<String> names = new ArrayList<>();
            for (JsonNode k : c.get("cookies")) {
                names.add(k.get("name").asString());
            }
            assertThat(r.setCookies().stream().map(h -> h.substring(0, h.indexOf('='))).toList()).as(what).isEqualTo(names);
        } else {
            JsonNode data = (c.has("returned") ? c.get("returned") : c.get("response")).get("data");
            int code = data.get("code").asInt();
            String msg = data.get("msg").asString();
            if (data.has("wait")) {
                assertThat(r.kind()).as(what).isEqualTo(MemberOAuthService.Callback.Kind.ERROR);
            } else {
                assertThat(r.kind()).as(what).isEqualTo(MemberOAuthService.Callback.Kind.JSON);
                assertThat(r.code()).as(what).isEqualTo(code);
            }
            assertThat(r.msg()).as(what).isEqualTo(msg);
        }
        // mac_user 每一行
        List<Map<String, String>> javaRows = users();
        JsonNode phpRows = c.get("after");
        assertThat(javaRows).as(what + " 行数").hasSize(phpRows.size());
        Set<String> seededIds = new java.util.HashSet<>();
        Map<String, JsonNode> seedById = new LinkedHashMap<>();
        for (JsonNode s : c.get("seedUsers")) {
            seededIds.add(s.get("user_id").asString());
            seedById.put(s.get("user_id").asString(), s);
        }
        long phpNow = c.get("now").asLong();
        for (int i = 0; i < javaRows.size(); i++) {
            Map<String, String> j = javaRows.get(i);
            JsonNode p = phpRows.get(i);
            String id = p.get("user_id").asString();
            String where = what + " user " + id;
            for (Map.Entry<String, String> e : j.entrySet()) {
                String col = e.getKey();
                String pv = p.get(col).asString();
                String jv = e.getValue();
                if (col.equals("user_pwd") && !seededIds.contains(id)) {
                    // D-B26-1：旧版 md5(time())，新版不可猜测的随机值
                    assertThat(pv).as(where + " 旧版密码是 md5(time())").isIn(md5Times(phpNow));
                    assertThat(jv).as(where).matches("[0-9a-f]{32}").isNotIn(md5Times(javaNow));
                } else if (col.equals("user_random")) {
                    String seeded = seedById.containsKey(id) && seedById.get(id).has("user_random")
                            ? seedById.get(id).get("user_random").asString() : "";
                    assertThat(jv.equals(seeded)).as(where + " user_random 是否轮换").isEqualTo(pv.equals(seeded));
                } else if (TIME_COLUMNS.contains(col) && Math.abs(Long.parseLong(pv) - phpNow) <= 3600) {
                    // 相对"现在"的时间（本次写入的当前时间、平移过的种子）：按相对值比较，容许几秒误差
                    long expected = Long.parseLong(pv) - phpNow + javaNow;
                    assertThat(Long.parseLong(jv)).as(where + " " + col).isBetween(expected - 5, expected + 5);
                } else {
                    assertThat(jv).as(where + " " + col).isEqualTo(pv);
                }
            }
        }
        // 登录成功时：新签发的 user_check 能通过校验（md5(user_random-user_name-user_id-)）
        if (r.kind() == MemberOAuthService.Callback.Kind.LOGGED_IN) {
            Map<String, String> cookies = new LinkedHashMap<>();
            for (String h : r.setCookies()) {
                String pair = h.split(";", 2)[0];
                cookies.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
            }
            Map<String, String> row = javaRows.stream().filter(u -> u.get("user_id").equals(cookies.get("user_id")))
                    .findFirst().orElseThrow();
            assertThat(cookies.get("user_check")).as(what).isEqualTo(md5(row.get("user_random") + "-" + row.get("user_name")
                    + "-" + row.get("user_id") + "-"));
        }
    }

    // ------------------------------------------------------------------ ② 安全用例

    @Test
    @Order(2)
    void forgedReplayedOrSessionlessStatesChangeNothingAndCallNobody() throws Exception {
        try (LoopbackMockServer mock = LoopbackMockServer.start()) {
            JsonNode c = load("php80-golden.json").get("cases").get(0);
            seed(c.get("seedUsers"), 0, 0);
            stub(mock, "qq", c.get("oauth"));
            Flow flow = flow(JsonMapper.builder().build().readTree("{}"), mock);
            List<Map<String, String>> before = users();
            SessionStore store = SessionStore.withDefaults(Clock.systemUTC());
            SessionState session = store.create();
            MemberState guest = MemberState.guest(null);
            String real = stateOf(flow.oauth().start("qq", guest, session));
            byte[] code = "c".getBytes(StandardCharsets.UTF_8);
            // 伪造
            assertThat(flow.oauth().callback("qq", code, "0".repeat(64).getBytes(), guest, session, CLIENT_IP).msg())
                    .isEqualTo("登录请求已失效，请重新登录");
            // 伪造已作废待用的 state：随后的真值也不行
            assertThat(flow.oauth().callback("qq", code, real.getBytes(), guest, session, CLIENT_IP).msg())
                    .isEqualTo("登录请求已失效，请重新登录");
            // 无会话 / 别的会话
            String other = stateOf(flow.oauth().start("qq", guest, store.create()));
            assertThat(flow.oauth().callback("qq", code, other.getBytes(), guest, null, CLIENT_IP).msg())
                    .isEqualTo("登录请求已失效，请重新登录");
            assertThat(flow.oauth().callback("qq", code, other.getBytes(), guest, store.create(), CLIENT_IP).msg())
                    .isEqualTo("登录请求已失效，请重新登录");
            assertThat(mock.requests()).as("拒绝发生在出站之前").isEmpty();
            assertThat(users()).as("库不变").isEqualTo(before);
            // 正常一次，然后重放
            String ok = stateOf(flow.oauth().start("qq", guest, session));
            assertThat(flow.oauth().callback("qq", code, ok.getBytes(), guest, session, CLIENT_IP).kind())
                    .isEqualTo(MemberOAuthService.Callback.Kind.LOGGED_IN);
            int requests = mock.requests().size();
            List<Map<String, String>> afterLogin = users();
            assertThat(flow.oauth().callback("qq", code, ok.getBytes(), guest, session, CLIENT_IP).msg())
                    .isEqualTo("登录请求已失效，请重新登录");
            assertThat(mock.requests()).hasSize(requests);
            assertThat(users()).isEqualTo(afterLogin);
            // 自动注册账号的密码不是 time()；普通登录入口凭 openid/col 不能登录（D2 ②）
            Map<String, String> created = afterLogin.get(afterLogin.size() - 1);
            String openid = created.get("user_openid_qq");
            assertThat(openid).isNotEmpty();
            long t = Long.parseLong(created.get("user_reg_time"));
            for (long g = t - 5; g <= t + 5; g++) {
                assertThat(flow.accounts().login(form("user_name", created.get("user_name"), "user_pwd", Long.toString(g)),
                        () -> true, CLIENT_IP).code()).isEqualTo(1003);
            }
            AccountReply byOpenid = flow.accounts().login(form("openid", openid, "col", "user_openid_qq"), () -> true, CLIENT_IP);
            assertThat(byOpenid.code()).isEqualTo(1001);
            assertThat(byOpenid.setCookies()).isEmpty();
            flow.accounts().register(form("user_name", "attacker01", "user_pwd", "p", "user_pwd2", "p",
                    "user_openid_qq", "X0X0X0X0X0"), Map.of(), () -> true, CLIENT_IP);
            assertThat(mapper.selectIdByOpenid(true, "X0X0X0X0X0")).as("注册入口不写 openid").isNull();
        }
    }

    @Test
    @Order(3)
    void concurrentCallbacksForOneNewOpenidRegisterOnce() throws Exception {
        JsonNode c = load("php80-golden.json").get("cases").get(0);
        seed(c.get("seedUsers"), 0, 0);
        MemberAccountService accounts = flow(JsonMapper.builder().build().readTree("{}"), null).accounts();
        String openid = "FEEDFACE00FEEDFACE00FEEDFACE0000";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<AccountReply>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> accounts.openidLogin(true, openid, CLIENT_IP));
            }
            int ok = 0;
            for (Future<AccountReply> f : pool.invokeAll(tasks)) {
                ok += f.get().ok() ? 1 : 0;
            }
            assertThat(ok).as("每次回调都登录了同一个账号").isEqualTo(8);
        } finally {
            pool.shutdownNow();
        }
        try (Connection conn = admin(dbName); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*), MAX(user_login_num) FROM mac_user WHERE user_openid_qq = ?")) {
            ps.setString(1, openid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("只注册了一个账号").isEqualTo(1);
                assertThat(rs.getInt(2)).isEqualTo(8);
            }
        }
    }

    // ------------------------------------------------------------------ ③ 回滚演练

    @Test
    @Order(4)
    void dmlRestoreBringsBackS0AndSchemaIsUnchanged() throws Exception {
        // 本批的写入各做一次：自动注册、凭 openid 登录、清掉他人的 openid、绑定
        long t = Instant.now().getEpochSecond();
        assertThat(writer.registerOpenid("drill00001", "0123456789abcdef0123456789abcdef", 10, 1, t, 2130706433L, 0, 0, true,
                "D0D0D0D0D0D0D0D0")).isEqualTo(1);
        assertThat(writer.registerOpenid("drill00002", "0123456789abcdef0123456789abcdef", 10, 1, t, 2130706433L, 0, 0, true,
                "D0D0D0D0D0D0D0D0")).as("同一 openid 不能再注册").isZero();
        Long id = mapper.selectIdByOpenid(true, "D0D0D0D0D0D0D0D0");
        assertThat(writer.recordOpenidLogin(id, true, "D0D0D0D0D0D0D0D0", "rnd", t, 2130706433L)).isEqualTo(1);
        assertThat(writer.setOpenid(id, false, "oDrill")).isEqualTo(1);
        assertThat(writer.clearOpenid(false, "oDrill")).isEqualTo(1);
        DatabaseSnapshot now;
        try (Connection c = admin(dbName)) {
            now = DatabaseSnapshot.take(c, TABLES);
        }
        assertThat(SnapshotDiff.between(s0, now).isEmpty()).as("本 IT 的写入确实改了数据").isFalse();
        List<DmlRestore.Statement> plan = DmlRestore.plan(s0, now);
        int applied;
        try (Connection c = admin(dbName)) {
            applied = DmlRestore.apply(c, plan);
        }
        System.out.println("[mysql-it] oauth DML restore statements applied: " + applied);
        assertThat(applied).isEqualTo(plan.size());
        try (Connection c = admin(dbName)) {
            assertThat(SnapshotDiff.between(s0, DatabaseSnapshot.take(c, TABLES)).isEmpty()).as("恢复后与 S0 逐行相同").isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).as("表结构指纹不变").isEmpty();
        }
    }

    // ------------------------------------------------------------------ 辅助

    private record Flow(MemberAccountService accounts, MemberOAuthService oauth) {
    }

    private static Flow flow(JsonNode overrides, LoopbackMockServer mock) throws IOException {
        Map<Object, Object> root = tree(repoConfig);
        overrides.properties().forEach(e -> put(root, e.getKey(), e.getValue().asString()));
        put(root, "connect.qq.status", "1");
        put(root, "connect.qq.key", QQ_ID);
        put(root, "connect.qq.secret", "test-only-qq-secret");
        put(root, "connect.weixin.status", "1");
        put(root, "connect.weixin.key", WX_ID);
        put(root, "connect.weixin.secret", "test-only-weixin-secret");
        PhpArray config = array(root);
        ThirdPartyConfig third = ThirdPartyConfig.from(config);
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), Duration.ofSeconds(3), 1 << 16,
                third.secretValues());
        String base = mock == null ? "http://127.0.0.1:9/" : mock.uri("/").toString();
        OAuthLogin oauth = OAuthConfiguration.create(client, third, base + "qq/", "https://open.weixin.example.test/",
                base + "wx/");
        MemberAccountService accounts = new MemberAccountService(mapper, writer, sessions,
                new PortraitStore(Files.createTempDirectory("b26").toString()), MemberSettings.from(config), Clock.systemUTC(),
                new SecureRandom(), "/");
        return new Flow(accounts, new MemberOAuthService(oauth, accounts, SITE, Clock.systemUTC(), new SecureRandom()));
    }

    /** 模拟服务按用例计划回复：QQ（令牌、openid、用户信息）或微信（令牌含 openid、用户信息）。 */
    private static void stub(LoopbackMockServer mock, String type, JsonNode plan) {
        boolean token = plan.get("token").asBoolean();
        String openid = plan.get("openid").asString();
        String info = plan.get("info").toString();
        if (type.equals("qq")) {
            mock.stub("POST", "/qq/oauth2.0/token", token
                    ? MockResponse.json(200, "{\"access_token\":\"test-only-token\",\"expires_in\":7776000}")
                    : MockResponse.text(200, "callback( {\"error\":100019,\"error_description\":\"code to access token error\"} );"));
            mock.stub("GET", "/qq/oauth2.0/me", MockResponse.text(200,
                    "callback( {\"client_id\":\"" + QQ_ID + "\",\"openid\":\"" + openid + "\"} );"));
            mock.stub("GET", "/qq/user/get_user_info", MockResponse.json(200, info));
        } else {
            mock.stub("GET", "/wx/sns/oauth2/access_token", token
                    ? MockResponse.json(200, "{\"access_token\":\"test-only-token\",\"expires_in\":7200,\"openid\":\"" + openid + "\"}")
                    : MockResponse.json(200, "{\"errcode\":40029,\"errmsg\":\"invalid code\"}"));
            mock.stub("GET", "/wx/sns/userinfo", MockResponse.json(200, info));
        }
    }

    private static String stateOf(String authorizeUrl) {
        assertThat(authorizeUrl).isNotNull();
        for (String pair : URI.create(authorizeUrl).getRawQuery().split("&")) {
            if (pair.startsWith("state=")) {
                return pair.substring(6);
            }
        }
        throw new IllegalStateException("授权页地址没有 state");
    }

    private static MemberState memberState(JsonNode seeds, int id) {
        for (JsonNode s : seeds) {
            if (s.get("user_id").asInt() == id) {
                return new MemberState(true, id, s.get("user_name").asString(), s.get("group_id").asInt(),
                        s.get("group_id").asInt(), null, 0, false);
            }
        }
        throw new IllegalStateException("种子里没有会员 " + id);
    }

    /** 清空 mac_user 后写入种子；时间列中距 {@code goldenNow} 一小时以内的值平移到 {@code now} 附近。 */
    private static void seed(JsonNode users, long goldenNow, long now) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE mac_user");
            for (JsonNode u : users) {
                List<String> cols = new ArrayList<>();
                u.properties().forEach(e -> cols.add(e.getKey()));
                String sql = "INSERT INTO mac_user (" + String.join(",", cols) + ") VALUES ("
                        + String.join(",", java.util.Collections.nCopies(cols.size(), "?")) + ")";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < cols.size(); i++) {
                        String v = u.get(cols.get(i)).asString();
                        if (goldenNow > 0 && TIME_COLUMNS.contains(cols.get(i))
                                && Math.abs(Long.parseLong(v) - goldenNow) <= 3600) {
                            v = Long.toString(Long.parseLong(v) - goldenNow + now);
                        }
                        ps.setString(i + 1, v);
                    }
                    ps.executeUpdate();
                }
            }
        }
    }

    private static final List<String> USER_COLUMNS = List.of("user_id", "group_id", "user_name", "user_pwd", "user_status",
            "user_points", "user_nick_name", "user_reg_ip", "user_reg_time", "user_login_num", "user_login_time",
            "user_login_ip", "user_last_login_time", "user_last_login_ip", "user_random", "user_openid_qq",
            "user_openid_weixin", "user_end_time");

    private static List<Map<String, String>> users() throws SQLException {
        List<Map<String, String>> out = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + String.join(",", USER_COLUMNS) + " FROM mac_user ORDER BY user_id")) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String v = rs.getString(i);
                    row.put(md.getColumnLabel(i), v == null ? "" : v);
                }
                out.add(row);
            }
        }
        return out;
    }

    private static List<String> md5Times(long around) {
        List<String> out = new ArrayList<>();
        for (long t = around - 5; t <= around + 5; t++) {
            out.add(md5(Long.toString(t)));
        }
        return out;
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, PhpInput.Value> form(String... kv) {
        Map<String, PhpInput.Value> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(kv[i], new PhpInput.Value(kv[i + 1].getBytes(StandardCharsets.UTF_8), false));
        }
        return out;
    }

    private static JsonNode load(String file) throws IOException {
        try (InputStream in = OAuthGoldenMySqlIT.class.getResourceAsStream("/integration/b26/" + file)) {
            assertThat(in).as(file).isNotNull();
            return JsonMapper.builder().build().readTree(in);
        }
    }

    private static Map<Object, Object> tree(PhpArray a) {
        Map<Object, Object> m = new LinkedHashMap<>();
        a.entries().forEach((k, v) -> m.put(k, v instanceof PhpArray child ? tree(child) : v));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<Object, Object> root, String dotted, String value) {
        String[] path = dotted.split("[.]");
        Map<Object, Object> cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            Object k = PhpArray.normalizeKey(path[i]);
            if (!(cur.get(k) instanceof Map)) {
                cur.put(k, new LinkedHashMap<Object, Object>());
            }
            cur = (Map<Object, Object>) cur.get(k);
        }
        cur.put(PhpArray.normalizeKey(path[path.length - 1]), new PhpValue.PhpString(value));
    }

    @SuppressWarnings("unchecked")
    private static PhpArray array(Map<Object, Object> m) {
        Map<Object, PhpValue> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> child ? array((Map<Object, Object>) child) : (PhpValue) v));
        return new PhpArray(out);
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        Assumptions.assumeTrue(v != null && !v.isBlank(), name + " 未设置，跳过 MySQL 集成测试");
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

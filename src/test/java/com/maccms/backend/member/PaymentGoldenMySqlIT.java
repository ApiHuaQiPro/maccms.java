package com.maccms.backend.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.PaymentGateways;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.pay.PayConfiguration;
import com.maccms.backend.integration.pay.PayGolden;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import com.maccms.backend.member.application.MemberPaymentService;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PayCallback;
import com.maccms.backend.member.application.view.PaymentContext;
import com.maccms.backend.member.application.view.PaymentStep;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * 在线支付的真实 MySQL 对照（B24，./mvnw verify -Pmysql-it；docs/architecture/33-b24-payment.md 第 5、9 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql → 表结构指纹与快照 S0 → 以可写数据源打开的应用
 * 上下文（正式登记表与写入护栏）取会员财务的 Mapper →
 * ① 逐条复放 PHP 7.3 黄金的 gopay 与回调用例：每个用例先写入黄金的种子会员与订单，再调用新版服务，比较提示/输出与 mac_order、mac_user、
 * mac_plog 的行（有意差异按第 10 节断言）→ ② 同一订单的重复回调并发到达只到账一次（多种支付方式混合）→ ③ 对账：订单金额与积分、
 * 会员积分、mac_plog 流水前后一致 → ④ 回滚演练：加积分被拒绝、写流水被拒绝时按相反顺序补偿，结果不确定时保留认领、重发不重复到账 →
 * ⑤ 只用 DML 恢复到 S0、逐行 diff 为空、表结构指纹不变 → 删库并确认不存在。微信统一下单只访问本机模拟服务。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final List<String> TABLES = List.of("mac_user", "mac_order", "mac_plog");
    /** 新版与 PHP 7.3 有意不同的回调用例 → 新版的回复（写库效果与旧版相同，见 33 文档 D-B24-4）。 */
    private static final Map<String, String> JAVA_NOTIFY = Map.of("notify.alipay.plog-overflow", "success2");

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static DatabaseSnapshot s0;
    private static Map<String, String> schemaAtImport;
    private static ConfigurableApplicationContext app;
    private static MemberFinanceMapper reader;
    private static MemberFinanceWriteMapper writer;
    private static LoopbackMockServer mock;
    private static JsonNode golden;

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
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : statements(INSTALL_SQL)) {
                st.execute(sql);
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
        reader = app.getBean(MemberFinanceMapper.class);
        writer = app.getBean(MemberFinanceWriteMapper.class);
        mock = LoopbackMockServer.start();
        golden = PayGolden.load(PayGolden.PHP73);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (mock != null) {
            mock.close();
        }
        if (app != null) {
            app.close();
        }
        assertThat(TestOutboundGuard.drainViolations()).as("只访问回环地址").isEmpty();
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

    private static MemberPaymentService service(ThirdPartyConfig config, MemberFinanceWriteMapper w) {
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), Duration.ofSeconds(5), 1 << 16,
                config.secretValues());
        PaymentGateways gateways = PayConfiguration.create(client, config, null, null, null,
                mock.uri("/pay/unifiedorder").toString());
        return new MemberPaymentService(reader, w, gateways, Clock.systemUTC());
    }

    private static final MemberState MEMBER7 = new MemberState(true, 7, "golden7", 2, 2, null, 0, false);

    private static PaymentContext context(boolean w3) {
        return new PaymentContext("127.0.0.1", PayGolden::notifyUrl, w3);
    }

    // ------------------------------------------------------------------ ① 黄金复放

    @Test
    @Order(1)
    void casesMatchPhp73Golden() throws Exception {
        int gopay = 0;
        int notify = 0;
        for (JsonNode c : golden.get("cases")) {
            String label = c.get("label").asString();
            String op = c.get("op").asString();
            if (op.equals("notify") && !c.get("method").asString().equals("POST")) {
                continue; // GET 是页面层的"支付完成！"（SitePaymentMvcTest）
            }
            seed();
            long now = Instant.now().getEpochSecond();
            if (op.equals("gopay")) {
                stub(c);
            }
            MemberPaymentService service = service(PayGolden.config(golden, c.get("configOverrides")), writer);
            if (op.equals("gopay")) {
                PaymentStep step = service.gopay(MEMBER7, PhpInput.parseQuery(c.get("query").asString()), context(true));
                assertGopay(label, c, step);
                gopay++;
            } else {
                String out = service.notify(new PayCallback(PayGolden.input(c), PayGolden.post(c), PayGolden.raw(c)));
                assertThat(out).as(label).isEqualTo(JAVA_NOTIFY.getOrDefault(label, c.get("output").asString()));
                notify++;
            }
            assertThat(rows(now)).as(label + " 写库效果").isEqualTo(goldenRows(c.get("after")));
        }
        System.out.println("[mysql-it] B24 payment golden PHP 7.3: gopay " + gopay + ", notify " + notify);
        assertThat(gopay).isEqualTo(28);
        assertThat(notify).isEqualTo(50);
    }

    private static void stub(JsonNode c) {
        mock.close();
        mock = LoopbackMockServer.start();
        JsonNode plan = c.get("mockPlan").get("api.mch.weixin.qq.com");
        if (plan != null && c.get("proxy").asString().equals("mock")) {
            mock.stub("POST", "/pay/unifiedorder", MockResponse.of(plan.get("status").asInt(), "text/plain",
                    plan.get("body").asString()));
        } else {
            // 拒绝连接：没有登记的路径之外再关掉服务
            mock.close();
        }
    }

    private static void assertGopay(String label, JsonNode c, PaymentStep step) {
        JsonNode response = c.get("response");
        JsonNode parsed = c.get("outputParsed");
        if (response != null && !response.isNull()) {
            assertThat(step).as(label).isEqualTo(new PaymentStep.Message(response.get("data").get("msg").asString()));
        } else if (parsed != null && !parsed.isNull()) {
            if (parsed.get("kind").asString().equals("redirect")) {
                assertThat(step).as(label).isEqualTo(new PaymentStep.Redirect(parsed.get("url").asString()));
            } else {
                PaymentStep.AutoPost p = (PaymentStep.AutoPost) step;
                assertThat(p.action()).as(label).isEqualTo(parsed.get("action").asString());
                List<String> fields = p.fields().stream().map(f -> f.name() + "=" + f.value()).toList();
                List<String> expected = new ArrayList<>();
                parsed.get("fields").forEach(f -> expected.add(f.get(0).asString() + "=" + f.get(1).asString()));
                assertThat(fields).as(label).isEqualTo(expected);
            }
        } else if (c.has("assignedPayment") && !c.get("assignedPayment").isNull()) {
            PaymentStep.Weixin w = (PaymentStep.Weixin) step;
            JsonNode a = c.get("assignedPayment");
            if (a.isObject()) {
                assertThat(w.codeUrl()).as(label).contains(a.get("code_url").asString());
                assertThat(w.order().code()).isEqualTo(a.get("out_trade_no").asString());
                assertThat(w.order().price()).isEqualTo(a.get("total_fee").asString());
            } else {
                assertThat(w.codeUrl()).as(label).isEmpty();
            }
        } else {
            assertThat(c.get("returned").asBoolean()).as(label).isTrue();
            assertThat(step).as(label + "（旧版空页面）").isEqualTo(new PaymentStep.Blank());
        }
    }

    @Test
    @Order(2)
    void w3ClosedTurnsRedirectMethodsIntoNotOpen() throws Exception {
        seed();
        mock.close();
        mock = LoopbackMockServer.start();
        mock.stub("POST", "/pay/unifiedorder", MockResponse.of(200, "text/xml", "<xml><return_code>SUCCESS</return_code>"
                + "<result_code>SUCCESS</result_code><code_url>weixin://wxpay/bizpayurl?pr=W3off</code_url></xml>"));
        MemberPaymentService service = service(PayGolden.config(golden, null), writer);
        for (String p : List.of("alipay", "codepay", "zhapay")) {
            assertThat(service.gopay(MEMBER7, form("order_id", "1", "order_code", code(1), "payment", p), context(false)))
                    .as(p).isEqualTo(new PaymentStep.Message("该支付选项未开启！"));
        }
        // 微信不属于 W3：照常统一下单
        PaymentStep w = service.gopay(MEMBER7, form("order_id", "1", "order_code", code(1), "payment", "weixin"),
                context(false));
        assertThat(((PaymentStep.Weixin) w).codeUrl()).contains("weixin://wxpay/bizpayurl?pr=W3off");
    }

    // ------------------------------------------------------------------ ② 并发重复回调

    @Test
    @Order(3)
    void concurrentDuplicateCallbacksCreditOnce() throws Exception {
        MemberPaymentService service = service(PayGolden.config(golden, null), writer);
        JsonNode alipay = find("notify.alipay.ok");
        JsonNode weixin = find("notify.weixin.ok");
        JsonNode codepay = find("notify.codepay.ok");
        JsonNode zhapay = find("notify.zhapay.ok");
        List<JsonNode> mixed = List.of(alipay, weixin, codepay, zhapay);
        for (int round = 0; round < 15; round++) {
            seed();
            var out = ConcurrentWrites.run(16, i -> {
                JsonNode c = mixed.get(i % mixed.size());
                return service.notify(new PayCallback(PayGolden.input(c), PayGolden.post(c), PayGolden.raw(c)));
            });
            assertThat(out.failures()).as("round " + round).isEmpty();
            assertThat(out.values()).as("round " + round + " 全部按成功回复").allSatisfy(v -> assertThat(v)
                    .isIn("success2", "success", "<xml><return_code><![CDATA[SUCCESS]]></return_code><return_msg>"
                            + "<![CDATA[OK]]></return_msg></xml>"));
            assertThat(count("SELECT COUNT(*) FROM mac_plog WHERE user_id = 7")).as("round " + round + " 流水").isEqualTo(1);
            assertThat(count("SELECT user_points FROM mac_user WHERE user_id = 7")).as("round " + round + " 积分")
                    .isEqualTo(120);
            assertThat(count("SELECT order_status FROM mac_order WHERE order_id = 1")).isEqualTo(1);
            assertReconciled();
        }
    }

    // ------------------------------------------------------------------ ③ 对账

    /**
     * 对账（33 文档第 5.4 节）：每个会员 {@code user_points = 种子积分 + Σ 已到账订单的 order_points}；本批写入的流水（类型 1）与已到账订单
     * 一一对应（积分超出 {@code plog_points} 的订单不写流水）；每张到账订单只有一条流水。
     */
    private static void assertReconciled() throws SQLException {
        Map<Long, Long> seedPoints = new LinkedHashMap<>();
        golden.get("seedUsers").forEach(u -> seedPoints.put(u.get("user_id").asLong(), u.get("user_points").asLong()));
        Map<Long, Long> seedStatus = new LinkedHashMap<>();
        golden.get("seedOrders").forEach(o -> seedStatus.put(o.get("order_id").asLong(), o.get("order_status").asLong()));
        for (Map.Entry<Long, Long> u : seedPoints.entrySet()) {
            long credited = 0;
            long withPlog = 0;
            try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                    "SELECT order_id, order_points FROM mac_order WHERE user_id = ? AND order_status = 1")) {
                ps.setLong(1, u.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (seedStatus.get(rs.getLong(1)) == 0) {
                            credited += rs.getLong(2);
                            withPlog += rs.getLong(2) <= 65_535 ? rs.getLong(2) : 0;
                        }
                    }
                }
            }
            assertThat(count("SELECT user_points FROM mac_user WHERE user_id = " + u.getKey())).as("会员 " + u.getKey() + " 积分")
                    .isEqualTo(u.getValue() + credited);
            assertThat(count("SELECT COALESCE(SUM(plog_points), 0) FROM mac_plog WHERE plog_type = 1 AND user_id = "
                    + u.getKey())).as("会员 " + u.getKey() + " 流水").isEqualTo(withPlog);
        }
        assertThat(count("SELECT COUNT(*) FROM mac_plog WHERE plog_type <> 1")).isZero();
    }

    // ------------------------------------------------------------------ ④ 回滚演练

    @Test
    @Order(4)
    void failedStepsAreCompensatedAndUnknownOutcomesKeepTheClaim() throws Exception {
        MemberPaymentService service = service(PayGolden.config(golden, null), writer);
        JsonNode alipay = find("notify.alipay.ok");
        PayCallback callback = new PayCallback(PayGolden.input(alipay), PayGolden.post(alipay), PayGolden.raw(alipay));

        // 加积分被服务端拒绝（int unsigned 溢出）→ 订单按条件恢复为未支付，回复 fail2（平台会重发）
        seed();
        update("UPDATE mac_user SET user_points = 4294967290 WHERE user_id = 7");
        assertThat(service.notify(callback)).isEqualTo("fail2");
        assertThat(count("SELECT order_status FROM mac_order WHERE order_id = 1")).isZero();
        assertThat(count("SELECT order_pay_time FROM mac_order WHERE order_id = 1")).isZero();
        assertThat(text("SELECT order_pay_type FROM mac_order WHERE order_id = 1")).isEmpty();
        assertThat(count("SELECT user_points FROM mac_user WHERE user_id = 7")).isEqualTo(4294967290L);
        assertThat(count("SELECT COUNT(*) FROM mac_plog")).isZero();
        // 积分恢复后重发：正常到账一次
        update("UPDATE mac_user SET user_points = 100 WHERE user_id = 7");
        assertThat(service.notify(callback)).isEqualTo("success2");
        assertReconciled();

        // 写流水被服务端拒绝（类型超出 tinyint）→ 先撤销加积分，再恢复订单
        seed();
        MemberPaymentService badPlog = service(PayGolden.config(golden, null), faulty("insertPlog", true));
        assertThat(badPlog.notify(callback)).isEqualTo("fail2");
        assertThat(count("SELECT order_status FROM mac_order WHERE order_id = 1")).isZero();
        assertThat(count("SELECT user_points FROM mac_user WHERE user_id = 7")).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM mac_plog")).isZero();
        assertThat(service.notify(callback)).isEqualTo("success2");
        assertReconciled();

        // 写流水时连接中断（结果不确定）→ 不补偿，订单与积分保留待对账；重发的回调在认领一步匹配 0 行，不会第二次加积分
        seed();
        MemberPaymentService lost = service(PayGolden.config(golden, null), faulty("insertPlog", false));
        assertThat(lost.notify(callback)).isEqualTo("fail2");
        assertThat(count("SELECT order_status FROM mac_order WHERE order_id = 1")).isEqualTo(1);
        assertThat(count("SELECT user_points FROM mac_user WHERE user_id = 7")).isEqualTo(120);
        assertThat(service.notify(callback)).as("重发：订单已支付完毕").isEqualTo("success2");
        assertThat(count("SELECT user_points FROM mac_user WHERE user_id = 7")).as("不重复加积分").isEqualTo(120);
        assertThat(count("SELECT COUNT(*) FROM mac_plog")).as("缺的流水由对账发现").isZero();
    }

    /**
     * 包一层写 Mapper：{@code method} 被调用时，{@code serverRejects} 为 true 则以超出范围的类型执行真实语句（服务端报 1264，语句肯定没有
     * 生效），否则直接抛出非 SQL 异常（模拟连接中断，结果不确定）。
     */
    private static MemberFinanceWriteMapper faulty(String method, boolean serverRejects) {
        return (MemberFinanceWriteMapper) Proxy.newProxyInstance(MemberFinanceWriteMapper.class.getClassLoader(),
                new Class<?>[]{MemberFinanceWriteMapper.class}, (proxy, m, a) -> {
                    if (m.getName().equals(method)) {
                        if (!serverRejects) {
                            throw new IllegalStateException("simulated connection loss");
                        }
                        a[1] = 999;
                    }
                    try {
                        return m.invoke(writer, a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ------------------------------------------------------------------ ⑤ 恢复

    @Test
    @Order(5)
    void dmlRestoreBringsBackS0AndSchemaIsUnchanged() throws Exception {
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
        System.out.println("[mysql-it] B24 payment DML restore statements applied: " + applied);
        try (Connection c = admin(dbName)) {
            assertThat(SnapshotDiff.between(s0, DatabaseSnapshot.take(c, TABLES)).isEmpty()).as("恢复后与 S0 逐行相同")
                    .isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).as("表结构指纹不变")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static JsonNode find(String label) {
        for (JsonNode c : golden.get("cases")) {
            if (c.get("label").asString().equals(label)) {
                return c;
            }
        }
        throw new IllegalStateException(label);
    }

    private static String code(long id) {
        return PayGolden.order(golden, id).get("order_code").asString();
    }

    private static Map<String, PhpInput.Value> form(String... kv) {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            q.append(i == 0 ? "" : "&").append(kv[i]).append('=').append(kv[i + 1]);
        }
        return PhpInput.parseQuery(q.toString());
    }

    /** 清空三张表，写入黄金的种子会员与订单（DML）。 */
    private static void seed() throws SQLException {
        for (String t : TABLES) {
            update("DELETE FROM " + t);
        }
        for (JsonNode u : golden.get("seedUsers")) {
            insert("mac_user", u);
        }
        for (JsonNode o : golden.get("seedOrders")) {
            insert("mac_order", o);
        }
    }

    private static void insert(String table, JsonNode row) throws SQLException {
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        row.properties().forEach(e -> {
            cols.add(e.getKey());
            vals.add(e.getValue().isNumber() ? e.getValue().asLong() : e.getValue().asString());
        });
        update("INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")", vals.toArray());
    }

    /** 三张表的比较形态：订单（支付时间记为 zero/recent/seed）、会员积分与组、流水（不含主键，时间记为 recent）。 */
    private static Map<String, List<String>> rows(long now) throws SQLException {
        Map<Long, Long> seedPayTime = new LinkedHashMap<>();
        golden.get("seedOrders").forEach(o -> seedPayTime.put(o.get("order_id").asLong(),
                o.has("order_pay_time") ? o.get("order_pay_time").asLong() : 0));
        Map<String, List<String>> out = new TreeMap<>();
        List<String> orders = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT order_id, user_id, order_status, order_code, order_price, order_points, order_pay_type, "
                        + "order_pay_time, order_remarks FROM mac_order ORDER BY order_id")) {
            while (rs.next()) {
                long t = rs.getLong(8);
                String when = t == 0 ? "zero" : t == seedPayTime.getOrDefault(rs.getLong(1), -1L) ? "seed"
                        : Math.abs(now - t) <= 120 ? "recent" : "other:" + t;
                orders.add(rs.getLong(1) + "|" + rs.getLong(2) + "|" + rs.getInt(3) + "|" + rs.getString(4) + "|"
                        + rs.getString(5) + "|" + rs.getLong(6) + "|" + rs.getString(7) + "|" + when + "|"
                        + rs.getString(9));
            }
        }
        out.put("orders", orders);
        out.put("users", list("SELECT CONCAT(user_id, '|', user_points, '|', group_id) FROM mac_user ORDER BY user_id"));
        List<String> plogs = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT user_id, plog_type, plog_points, plog_remarks, plog_time FROM mac_plog ORDER BY plog_id")) {
            while (rs.next()) {
                plogs.add(rs.getLong(1) + "|" + rs.getInt(2) + "|" + rs.getLong(3) + "|" + rs.getString(4) + "|"
                        + (Math.abs(now - rs.getLong(5)) <= 120 ? "recent" : "other"));
            }
        }
        out.put("plogs", plogs);
        return out;
    }

    private static Map<String, List<String>> goldenRows(JsonNode after) {
        Map<String, List<String>> out = new TreeMap<>();
        List<String> orders = new ArrayList<>();
        for (JsonNode o : after.get("orders")) {
            JsonNode age = o.get("order_pay_age");
            String when = o.get("order_pay_time_zero").asBoolean() ? "zero"
                    : Math.abs(age.asLong()) <= 120 ? "recent" : "seed";
            orders.add(o.get("order_id").asLong() + "|" + o.get("user_id").asLong() + "|" + o.get("order_status").asInt()
                    + "|" + o.get("order_code").asString() + "|" + o.get("order_price").asString() + "|"
                    + o.get("order_points").asLong() + "|" + o.get("order_pay_type").asString() + "|" + when + "|"
                    + o.get("order_remarks").asString());
        }
        out.put("orders", orders);
        List<String> users = new ArrayList<>();
        after.get("users").forEach(u -> users.add(u.get("user_id").asLong() + "|" + u.get("user_points").asLong() + "|"
                + u.get("group_id").asInt()));
        out.put("users", users);
        List<String> plogs = new ArrayList<>();
        after.get("plogs").forEach(p -> plogs.add(p.get("user_id").asLong() + "|" + p.get("plog_type").asInt() + "|"
                + p.get("plog_points").asLong() + "|" + p.get("plog_remarks").asString() + "|"
                + (Math.abs(p.get("plog_age").asLong()) <= 120 ? "recent" : "other")));
        out.put("plogs", plogs);
        return out;
    }

    private static List<String> list(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void update(String sql, Object... args) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("缺少环境变量 " + name + "：MySQL 集成测试需要外部提供管理账号，见 README.md");
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

    private static List<String> statements(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\\r?\\n")) {
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

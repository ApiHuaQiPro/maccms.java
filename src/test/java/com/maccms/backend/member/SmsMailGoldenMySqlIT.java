package com.maccms.backend.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.admin.application.MailTestService;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.MailSender;
import com.maccms.backend.integration.application.SmsSender;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.mail.MailConfiguration;
import com.maccms.backend.integration.sms.SmsConfiguration;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.LoopbackMockServer.RecordedRequest;
import com.maccms.backend.integration.testing.LoopbackSmtpServer;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberSettings;
import com.maccms.backend.member.application.MemberVerification;
import com.maccms.backend.member.application.VerificationSettings;
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
import java.net.ServerSocket;
import java.net.URLDecoder;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocketFactory;
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
 * 短信/邮件验证码的真实 MySQL 对照（B25，./mvnw verify -Pmysql-it；32 文档第 8 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql → 快照 S0 → 以可写数据源打开的应用上下文
 * （正式登记表）取会员的只读/写 Mapper → ① 逐条复放 PHP 7.3 与 8.0 的黄金（{@code integration/b25/php{73,80}-golden.json}，各 98 个用例，
 * 由 {@code src/test/php/sms_mail_golden.php} 生成）：每个用例清空 mac_user、mac_msg，按黄金写入会员与验证码记录，用同样的配置（仓库
 * maccms.php + 黄金的覆盖项）在本机模拟服务（{@link LoopbackMockServer} 扮演阿里云与腾讯云，{@link LoopbackSmtpServer} 扮演 SMTP）上调用
 * 新版，比较返回码与提示原文、mac_msg 与 mac_user 的结果、通道收到的请求（参数、签名可用假密钥复算、验证码与记录一致）；
 * 有意差异（32 文档第 9 节）按新版的结果断言 → ② 只用 DML 恢复到 S0、逐行 diff 为空、表结构指纹不变 → 删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SmsMailGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Path MACCMS_PHP = Path.of("php", "application", "extra", "maccms.php");
    private static final List<String> TABLES = List.of("mac_user", "mac_msg");
    private static final List<String> GOLDENS = List.of("php73-golden.json", "php80-golden.json");
    private static final String CLIENT_IP = "127.0.0.1";
    private static final String ALIYUN_HOST = "dysmsapi.aliyuncs.com";
    private static final String QCLOUD_HOST = "yun.tim.qq.com";
    private static final String ALIYUN_SECRET = "test-only-aliyun-secret";
    private static final String QCLOUD_KEY = "test-only-qcloud-key";
    private static final String SMTP_PASS = "test-only-smtp-password";

    /** 有意差异（32 文档第 9 节）：用例 → 新版每次调用的 code 与 msg；这些用例的库结果另行断言。 */
    private static final Map<String, List<String>> JAVA_RESULT = Map.ofEntries(
            // D-B25-3：腾讯云按返回结果判定（旧版任何结果都算成功并记下验证码）
            Map.entry("send-reg-qcloud-error-1014", List.of("9009 验证码发送失败：package format error")),
            Map.entry("send-reg-qcloud-http-500", List.of("9009 验证码发送失败：500 ")),
            Map.entry("send-reg-qcloud-closed", List.of("9009 验证码发送失败：")),
            Map.entry("send-reg-qcloud-refused", List.of("9009 验证码发送失败：")),
            // D-B25-1：旧版缺 PHPMailer 报致命错误；新版经 SMTP 发出（本机模拟服务）
            Map.entry("email-reg-phpmailer", List.of("1 验证码发送成功，请前往查看")),
            Map.entry("email-findpass-phpmailer", List.of("1 验证码发送成功，请前往查看")),
            Map.entry("test-email-phpmailer", List.of("1 测试成功")),
            // D-B25-4：验证码必须是发给本次使用的地址的
            Map.entry("reset-code-for-other-to", List.of("9002 验证信息错误，请重试")),
            Map.entry("reset-phone-ac-email-code", List.of("9002 验证信息错误，请重试")),
            Map.entry("bind-code-for-other-to", List.of("9002 验证信息错误，请重试")),
            Map.entry("register-phone-code-for-other-to", List.of("9002 验证信息错误，请重试")));
    /** 新版发出了邮件（SMTP），旧版是致命错误：库里多一条验证码记录。 */
    private static final Set<String> MAIL_SENT = Set.of("email-reg-phpmailer", "email-findpass-phpmailer");

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
    void casesMatchPhpGolden() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        try (LoopbackMockServer mock = LoopbackMockServer.start()) {
            Map<String, JsonNode> plan = new java.util.concurrent.ConcurrentHashMap<>();
            mock.stub("POST", "/", r -> respond(plan.get(ALIYUN_HOST)));
            mock.stub("POST", "/v5/tlssmssvr/sendsms", r -> respond(plan.get(QCLOUD_HOST)));
            for (String file : GOLDENS) {
                JsonNode golden = load(file);
                int compared = 0;
                for (JsonNode c : golden.get("cases")) {
                    assertThat(c.get("setupError").isNull()).as(file + " " + c.get("label").asString() + " PHP 侧准备成功").isTrue();
                    plan.clear();
                    c.get("mockPlan").properties().forEach(e -> plan.put(e.getKey(), e.getValue()));
                    try (LoopbackSmtpServer smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options()
                            .account("golden@example.test", SMTP_PASS))) {
                        replay(file, golden, c, mock, smtp, deadPort);
                    }
                    compared++;
                }
                assertThat(compared).as(file).isEqualTo(98);
            }
        }
        assertThat(TestOutboundGuard.drainViolations()).as("只访问回环地址").isEmpty();
    }

    private void replay(String file, JsonNode golden, JsonNode c, LoopbackMockServer mock, LoopbackSmtpServer smtp, int deadPort)
            throws Exception {
        String label = c.get("label").asString();
        String what = file + " " + label;
        long now = seed(c);
        PhpArray config = config(golden.get("baseConfig"), c.get("configOverrides"), smtp.port());
        ThirdPartyConfig third = ThirdPartyConfig.from(config);
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 16,
                third.secretValues());
        boolean refuseAll = c.get("proxy").asString().equals("refuse");
        String dead = "http://127.0.0.1:" + deadPort;
        String aliyun = refuseAll || closes(c, ALIYUN_HOST) ? dead + "/" : mock.uri("/").toString();
        String qcloud = refuseAll || closes(c, QCLOUD_HOST) ? dead + "/v5/tlssmssvr/sendsms"
                : mock.uri("/v5/tlssmssvr/sendsms").toString();
        SmsSender sms = SmsConfiguration.create(client, third, aliyun, qcloud, Clock.systemUTC());
        MailSender mail = MailConfiguration.create(third, OutboundPolicy.Mode.LOOPBACK_ONLY, Duration.ofSeconds(2),
                Duration.ofSeconds(5), (SSLSocketFactory) SSLSocketFactory.getDefault(), Clock.systemUTC());
        MemberVerification verification = new MemberVerification(mapper, writer, sms, mail, VerificationSettings.from(config),
                Clock.systemUTC(), new SecureRandom());
        MemberAccountService service = new MemberAccountService(mapper, writer, sessions,
                new PortraitStore(Files.createTempDirectory("b25").toString()), MemberSettings.from(config), Clock.systemUTC(),
                new SecureRandom(), "/", inviter -> { }, (f, t, z) -> { }, verification);
        MemberState user = c.get("as").asInt() == 0 ? MemberState.guest(null)
                : new MemberState(true, c.get("user").get("user_id").asLong(), c.get("user").get("user_name").asString(), 2, 2,
                        null, 0, false);
        int requestsBefore = mock.requests().size();
        List<String> javaResults = new ArrayList<>();
        for (JsonNode call : c.get("calls")) {
            Map<String, PhpInput.Value> params = params(call.get("params"));
            String op = call.get("op").asString();
            String r = switch (op) {
                case "reg_msg" -> text(service.sendMessage(user, params, 3));
                case "findpass_msg" -> text(service.sendMessage(user, params, 2));
                case "bindmsg" -> text(service.sendMessage(user, params, 1));
                case "findpass_reset" -> text(service.findpassReset(user, params));
                case "bind" -> text(service.bind(user, params));
                case "unbind" -> text(service.unbind(user, params));
                case "register" -> {
                    AccountReply reg = service.register(user, params, Map.of(), () -> false, CLIENT_IP);
                    // 旧控制器注册成功后立即登录（index/controller/User.php:361-364）；黄金调用的是模型，成功时提示不同
                    yield reg.ok() && reg.msg().equals("注册成功，登录成功") ? "1 注册成功,请登录去会员中心完善个人信息" : text(reg);
                }
                case "test_email" -> {
                    MailTestService.Result t = new MailTestService(mail, () -> config, new SecureRandom())
                            .test(scalar(params, "type"), scalar(params, "nick"), scalar(params, "test"));
                    yield t.code() + " " + t.msg();
                }
                default -> throw new IllegalStateException(op);
            };
            javaResults.add(r);
        }
        List<RecordedRequest> requests = mock.requests().subList(requestsBefore, mock.requests().size());
        if (JAVA_RESULT.containsKey(label)) {
            assertThat(javaResults).as(what + "（有意差异）").isEqualTo(JAVA_RESULT.get(label));
            intentional(what, label, c, smtp, now);
            return;
        }
        List<String> phpResults = new ArrayList<>();
        for (JsonNode call : c.get("calls")) {
            assertThat(call.has("fatal")).as(what + " PHP 侧不是致命错误").isFalse();
            phpResults.add(call.get("code").asInt() + " " + call.get("msg").asString());
        }
        assertThat(javaResults).as(what).isEqualTo(phpResults);
        List<Map<String, Object>> msgs = msgRows(now);
        compareMsgs(what, c.get("msgsAfter"), msgs);
        compareUsers(what, c.get("usersAfter"), c.get("seedUsers"));
        compareRequests(what, c.get("requests"), requests, msgs);
        if (label.startsWith("reset-") && javaResults.get(0).startsWith("1 ")) {
            // D2 ⑥：找回改密时轮换 user_random（旧版不换）
            assertThat(userRandom(c.get("calls").get(0).get("params"))).as(what + " user_random 已轮换").isNotEqualTo("r7");
        }
    }

    /** 有意差异用例的库结果：没有发出（或旧版错误记下）的验证码不记；拒绝的找回/绑定/注册不改会员；新版发出的邮件记下验证码。 */
    private void intentional(String what, String label, JsonNode c, LoopbackSmtpServer smtp, long now) throws SQLException {
        List<Map<String, Object>> msgs = msgRows(now);
        long seeded = c.get("seedMsgs").size();
        if (MAIL_SENT.contains(label)) {
            assertThat(msgs).as(what).hasSize((int) seeded + 1);
            Map<String, Object> row = msgs.get(msgs.size() - 1);
            String code = (String) row.get("msg_code");
            assertThat(code).matches("[0-9]{6}");
            String flag = label.contains("findpass") ? "找回" : "注册";
            assertThat(row.get("msg_content")).as(what).isEqualTo("【黄金样本站】的会员您好，" + flag + "验证码为：" + code
                    + "，请在5分钟内完成验证。");
            assertThat(smtp.sessions(1)).as(what).hasSize(1);
            String message = new String(smtp.sessions().get(0).messages().get(0), StandardCharsets.US_ASCII);
            String body = new String(Base64.getMimeDecoder().decode(message.substring(message.indexOf("\r\n\r\n") + 4)),
                    StandardCharsets.UTF_8);
            assertThat(body).isEqualTo(row.get("msg_content"));
            assertThat(message).contains("To: a@example.com", "<golden@example.test>");
            return;
        }
        if (label.equals("test-email-phpmailer")) {
            assertThat(smtp.sessions(1)).as(what).hasSize(1);
            String message = new String(smtp.sessions().get(0).messages().get(0), StandardCharsets.US_ASCII);
            String body = new String(Base64.getMimeDecoder().decode(message.substring(message.indexOf("\r\n\r\n") + 4)),
                    StandardCharsets.UTF_8);
            assertThat(body).isEqualTo("【黄金样本站】当您看到这封邮件说明邮件配置正确了！感谢支持开源程序！");
            assertThat(message).contains("To: golden@example.test", "From: =?UTF-8?B?"
                    + Base64.getEncoder().encodeToString("黄金样本".getBytes(StandardCharsets.UTF_8)));
            return;
        }
        assertThat(msgs).as(what + " 没有新的验证码记录").hasSize((int) seeded);
        // 会员行与种子相同（没有改密、改绑或新注册）
        compareUsers(what, c.get("seedUsers"), c.get("seedUsers"));
        try (Connection conn = admin(dbName); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM mac_user")) {
            rs.next();
            assertThat(rs.getInt(1)).as(what + " 没有新会员").isEqualTo(c.get("seedUsers").size());
        }
    }

    // ------------------------------------------------------------------ 比较

    private static void compareMsgs(String what, JsonNode expected, List<Map<String, Object>> actual) {
        assertThat(actual).as(what + " mac_msg 行数").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            Map<String, Object> a = actual.get(i);
            String w = what + " mac_msg[" + i + "]";
            assertThat(a.get("user_id")).as(w).isEqualTo(e.get("user_id").asLong());
            assertThat(a.get("msg_type")).as(w).isEqualTo(e.get("msg_type").asLong());
            assertThat(a.get("msg_status")).as(w).isEqualTo(e.get("msg_status").asLong());
            assertThat(a.get("msg_to")).as(w).isEqualTo(e.get("msg_to").asString());
            if (e.get("seeded").asBoolean()) {
                assertThat(a.get("msg_code")).as(w).isEqualTo(e.get("msg_code").asString());
                assertThat(a.get("msg_content")).as(w).isEqualTo(e.get("msg_content").asString());
                assertThat(a.get("age")).as(w).isEqualTo(e.get("age").asLong());
            } else {
                String code = (String) a.get("msg_code");
                assertThat(code).as(w).matches("[0-9]{6}");
                assertThat(((String) a.get("msg_content")).replace(code, "{CODE}")).as(w)
                        .isEqualTo(e.get("msg_content").asString().replace(e.get("msg_code").asString(), "{CODE}"));
                assertThat((long) a.get("age")).as(w + " 新记录的时间").isBetween(-10L, 0L);
            }
        }
    }

    private static void compareUsers(String what, JsonNode expected, JsonNode seeds) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM mac_user ORDER BY user_id")) {
            List<Map<String, String>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getString(i));
                }
                rows.add(row);
            }
            assertThat(rows).as(what + " mac_user 行数").hasSize(expected.size());
            Set<Long> seeded = new java.util.HashSet<>();
            seeds.forEach(s -> seeded.add(s.get("user_id").asLong()));
            for (int i = 0; i < expected.size(); i++) {
                JsonNode e = expected.get(i);
                Map<String, String> a = rows.get(i);
                for (var f : e.properties()) {
                    if (f.getKey().equals("user_id") && !seeded.contains(f.getValue().asLong())) {
                        // 新注册的会员：MyISAM 自增值在清表后不回退，编号与旧版不同
                        continue;
                    }
                    if (f.getKey().equals("user_random")) {
                        continue;
                    }
                    assertThat(a.get(f.getKey())).as(what + " mac_user[" + i + "]." + f.getKey()).isEqualTo(f.getValue().asString());
                }
            }
        }
    }

    /** 通道收到的请求：方法、路径、参数（随机项除外）逐项相同，签名可用假密钥复算，验证码与记下的相同。 */
    private static void compareRequests(String what, JsonNode expected, List<RecordedRequest> actual,
                                        List<Map<String, Object>> msgs) {
        List<JsonNode> sent = new ArrayList<>();
        // 旧版连接被拒或被关闭时请求没有到达模拟服务（新版指向不监听的端口，同样没有记录）
        expected.forEach(r -> {
            if (!r.has("mockAction") || !r.get("mockAction").asString().equals("close")) {
                sent.add(r);
            }
        });
        assertThat(actual).as(what + " 通道收到的请求数").hasSize(sent.size());
        Set<String> codes = new java.util.HashSet<>();
        msgs.forEach(m -> codes.add((String) m.get("msg_code")));
        for (int i = 0; i < sent.size(); i++) {
            JsonNode e = sent.get(i);
            RecordedRequest a = actual.get(i);
            String w = what + " request[" + i + "]";
            assertThat(a.method()).as(w).isEqualTo(e.get("method").asString());
            assertThat(a.path()).as(w).isEqualTo(e.get("path").asString());
            assertThat(a.header("content-type")).as(w).isEqualTo(header(e, "content-type"));
            if (e.get("host").asString().equals(ALIYUN_HOST)) {
                Map<String, String> p = form(a.bodyText());
                List<String> names = new ArrayList<>();
                e.get("paramNames").forEach(n -> names.add(n.asString()));
                assertThat(new ArrayList<>(p.keySet())).as(w).isEqualTo(names);
                Map<String, String> ep = new LinkedHashMap<>();
                e.get("params").properties().forEach(f -> ep.put(f.getKey(), f.getValue().asString()));
                String code = codeOf(p.get("TemplateParam"));
                for (var f : ep.entrySet()) {
                    String k = f.getKey();
                    if (k.equals("Signature") || k.equals("SignatureNonce") || k.equals("Timestamp")) {
                        continue;
                    }
                    String v = k.equals("TemplateParam") ? f.getValue().replace(codeOf(f.getValue()), code) : f.getValue();
                    assertThat(p.get(k)).as(w + " " + k).isEqualTo(v);
                }
                assertThat(p.get("Timestamp")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ");
                assertThat(a.bodyText()).as(w + " 签名").isEqualTo(resignAliyun(p));
                assertThat(e.get("signatureValid").asBoolean()).as(w + " PHP 签名").isTrue();
                if (e.get("codeMatchesStoredMsg").asBoolean()) {
                    assertThat(codes).as(w + " 验证码已记下").contains(code);
                }
            } else {
                assertThat(a.query()).as(w).matches("sdkappid=1400000000&random=[1-9][0-9]{5}");
                JsonNode body = readTree(a.bodyText());
                String code = body.get("params").get(0).asString();
                String elided = a.bodyText().replace(body.get("sig").asString(), "<sig>")
                        .replace("\"time\":" + body.get("time").asLong(), "\"time\":<time>")
                        .replace("[\"" + code + "\"]", "[\"<code>\"]");
                String phpCode = e.get("code").asString();
                assertThat(elided).as(w).isEqualTo(e.get("bodyElided").asString().replace("[\"" + phpCode + "\"]", "[\"<code>\"]"));
                int random = Integer.parseInt(a.query().substring(a.query().indexOf("random=") + 7));
                assertThat(body.get("sig").asString()).as(w + " sig").isEqualTo(sha256("appkey=" + QCLOUD_KEY + "&random=" + random
                        + "&time=" + body.get("time").asLong() + "&mobile=" + body.get("tel").get("mobile").asString()));
                if (e.get("codeMatchesStoredMsg").asBoolean()) {
                    assertThat(codes).as(w + " 验证码已记下").contains(code);
                }
            }
        }
    }

    // ------------------------------------------------------------------ ② 回滚演练

    @Test
    @Order(2)
    void dmlRestoreBringsBackS0AndSchemaIsUnchanged() throws Exception {
        // 本批的写入各做一次（最后一个黄金用例不留数据）：记验证码、带手机注册、绑定邮箱、清掉同号、找回改密
        long t = Instant.now().getEpochSecond();
        assertThat(writer.insertMessage(0, 3, "13800000099", "123456", "drill", t)).isEqualTo(1);
        assertThat(writer.registerWithContact("drill0001", "0123456789abcdef0123456789abcdef", 10, 1, t, 2130706433L, 0, 0,
                "13800000099", null)).isEqualTo(1);
        assertThat(writer.registerWithContact("drill0002", "0123456789abcdef0123456789abcdef", 10, 1, t, 2130706433L, 0, 0,
                "13800000099", null)).as("同号不能再注册").isZero();
        Long id = mapper.selectIdByPhone("13800000099");
        assertThat(writer.setEmail(id, "drill@example.test")).isEqualTo(1);
        assertThat(writer.resetPasswordByPhone("13800000099", "fedcba9876543210fedcba9876543210", "rnd")).isEqualTo(1);
        assertThat(writer.clearEmail("drill@example.test")).isEqualTo(1);
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
        System.out.println("[mysql-it] sms/mail DML restore statements applied: " + applied);
        assertThat(applied).isEqualTo(plan.size());
        try (Connection c = admin(dbName)) {
            assertThat(SnapshotDiff.between(s0, DatabaseSnapshot.take(c, TABLES)).isEmpty()).as("恢复后与 S0 逐行相同").isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).as("表结构指纹不变").isEmpty();
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static MockResponse respond(JsonNode plan) {
        if (plan == null) {
            return MockResponse.json(599, "unplanned");
        }
        String action = plan.get("action").asString();
        if (action.equals("hang")) {
            return MockResponse.json(200, "{\"Code\":\"OK\"}").withDelay(Duration.ofSeconds(4));
        }
        return MockResponse.of(plan.get("status").asInt(), "application/json", plan.get("body").asString());
    }

    private static boolean closes(JsonNode c, String host) {
        JsonNode p = c.get("mockPlan").get(host);
        return p != null && p.get("action").asString().equals("close");
    }

    /** 清空两张表，写入黄金的会员与验证码记录（{@code msg_time = now - age}）；返回本用例的参考时间。 */
    private static long seed(JsonNode c) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection conn = admin(dbName); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM mac_user");
            st.execute("DELETE FROM mac_msg");
            for (JsonNode u : c.get("seedUsers")) {
                insert(conn, "mac_user", u, null, now);
            }
            for (JsonNode m : c.get("seedMsgs")) {
                insert(conn, "mac_msg", m, "age", now);
            }
        }
        return now;
    }

    private static void insert(Connection conn, String table, JsonNode row, String ageKey, long now) throws SQLException {
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (var e : row.properties()) {
            if (e.getKey().equals(ageKey)) {
                cols.add("msg_time");
                vals.add(now - e.getValue().asLong());
            } else {
                cols.add(e.getKey());
                vals.add(e.getValue().isNumber() ? (Object) e.getValue().asLong() : e.getValue().asString());
            }
        }
        String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < vals.size(); i++) {
                ps.setObject(i + 1, vals.get(i));
            }
            ps.executeUpdate();
        }
    }

    private static List<Map<String, Object>> msgRows(long now) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_id, msg_type, msg_status, msg_to, msg_code, msg_content, msg_time "
                     + "FROM mac_msg ORDER BY msg_id")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("user_id", rs.getLong(1));
                m.put("msg_type", rs.getLong(2));
                m.put("msg_status", rs.getLong(3));
                m.put("msg_to", rs.getString(4));
                m.put("msg_code", rs.getString(5));
                m.put("msg_content", rs.getString(6));
                m.put("age", now - rs.getLong(7));
                out.add(m);
            }
        }
        return out;
    }

    private static String userRandom(JsonNode params) throws SQLException {
        String col = params.get("ac").asString().equals("email") ? "user_email" : "user_phone";
        String to = params.has("user_email") && !params.get("user_email").asString().isEmpty()
                ? params.get("user_email").asString() : params.get("to").asString();
        try (Connection c = admin(dbName);
             PreparedStatement ps = c.prepareStatement("SELECT user_random FROM mac_user WHERE " + col + " = ?")) {
            ps.setString(1, to);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 仓库 maccms.php → 黄金的基础配置 → 用例覆盖项；邮件指向本机模拟 SMTP（旧版发信前就报错，这些值不影响旧版结果）。 */
    private static PhpArray config(JsonNode base, JsonNode overrides, int smtpPort) {
        Map<Object, Object> root = tree(repoConfig);
        base.properties().forEach(e -> put(root, e.getKey(), e.getValue().asString()));
        overrides.properties().forEach(e -> put(root, e.getKey(), e.getValue().asString()));
        put(root, "email.phpmailer.host", "127.0.0.1");
        put(root, "email.phpmailer.port", String.valueOf(smtpPort));
        put(root, "email.phpmailer.secure", "");
        put(root, "email.phpmailer.password", SMTP_PASS);
        return array(root);
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

    private static Map<String, PhpInput.Value> params(JsonNode p) {
        Map<String, PhpInput.Value> out = new LinkedHashMap<>();
        p.properties().forEach(e -> out.put(e.getKey(), new PhpInput.Value(e.getValue().asString().getBytes(StandardCharsets.UTF_8),
                false)));
        return out;
    }

    private static String scalar(Map<String, PhpInput.Value> params, String name) {
        PhpInput.Value v = params.get(name);
        return v == null ? "" : new String(v.bytes(), StandardCharsets.UTF_8);
    }

    private static String text(AccountReply r) {
        return r.code() + " " + r.msg();
    }

    private static String header(JsonNode request, String name) {
        for (JsonNode h : request.get("headers")) {
            if (h.get(0).asString().equals(name)) {
                return h.get(1).asString();
            }
        }
        return null;
    }

    private static String codeOf(String templateParam) {
        Matcher m = Pattern.compile("\"code\":\"([0-9]+)\"").matcher(templateParam);
        return m.find() ? m.group(1) : "";
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** 按旧 Aliyun.php 的规则（去掉 Signature、按键名字节序排序、urlencode 后换 %20/%2A/~）用假密钥重新签名。 */
    private static String resignAliyun(Map<String, String> params) {
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>((x, y) -> java.util.Arrays.compareUnsigned(
                x.getBytes(StandardCharsets.UTF_8), y.getBytes(StandardCharsets.UTF_8)));
        params.forEach((k, v) -> {
            if (!k.equals("Signature")) {
                sorted.put(k, v);
            }
        });
        StringBuilder q = new StringBuilder();
        sorted.forEach((k, v) -> q.append('&').append(enc(k)).append('=').append(enc(v)));
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec((ALIYUN_SECRET + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String sig = Base64.getEncoder().encodeToString(mac.doFinal(("POST&%2F&" + enc(q.substring(1)))
                    .getBytes(StandardCharsets.UTF_8)));
            return "Signature=" + enc(sig) + q;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readTree(String s) {
        return JsonMapper.builder().build().readTree(s);
    }

    private static JsonNode load(String file) throws IOException {
        try (InputStream in = SmsMailGoldenMySqlIT.class.getResourceAsStream("/integration/b25/" + file)) {
            return JsonMapper.builder().build().readTree(in);
        }
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

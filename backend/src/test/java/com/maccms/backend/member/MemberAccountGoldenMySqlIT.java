package com.maccms.backend.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberCookies;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberSettings;
import com.maccms.backend.member.application.UserCheck;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.application.view.PortraitReply;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
import com.maccms.backend.writedrill.SnapshotDiff;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
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

/**
 * 会员账号的真实 MySQL 对照（B09，./mvnw verify -Pmysql-it；18 文档第 3、5、6、12 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql → 取表结构指纹与快照 S0 → 以可写数据源打开的
 * 应用上下文（正式登记表）取会员的只读/写 Mapper →
 * ① 逐条复放 PHP 8.0 与 7.3 的账号黄金（注册、登录、资料、找回、regcheck、短信/邮件"未配置"）：每个用例先按黄金写入它自己的会员行，
 * 再比较返回码、提示原文、库里存的密码与各字段（03 第 4.3 (1) 节的逐入口字节语义）；
 * ② D2 ①②⑥；③ 同名并发注册只插入 1 行；④ 到期单行降级并发只命中 1 次、续费不误降、组/游客 cookie 字节与 PHP 黄金一致；
 * ⑤ 新签发的 cookie 交给旧 PHP label_user（设置了 MACCMS_IT_PHP_CGI 时）；⑥ 头像；⑦ 只用 DML 恢复到 S0、逐行 diff 为空、
 * 表结构指纹不变 → 删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemberAccountGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final List<String> TABLES = List.of("mac_user", "mac_group", "mac_plog");
    private static final String GOLDEN80 = "member/account-php80-golden.json";
    private static final String GOLDEN73 = "member/account-php73-golden.json";
    private static final String CLIENT_IP = "127.0.0.1";

    /** PHP 与新版结果不同的用例（有意差异，18 文档第 10 节）→ 新版的返回码。 */
    private static final Map<String, Integer> JAVA_CODE = Map.of(
            "reg-pwd-numeric-loose", 1004,      // D-B09-6：两次密码按字节比较
            "reg-openid-skips-verify", 1003,    // D-B09-2：忽略 user_openid_*，验证码照常要求
            "login-stored-hash", 1003,          // D-B09-1（D2 ①）：库内 md5 不能当密码
            "login-openid", 1001);              // D-B09-2（D2 ②）：忽略 openid/col
    /** 成功时新版轮换 user_random、旧版不换的用例（D2 ⑥）。 */
    private static final Set<String> ROTATES = Set.of("info-change-pwd", "findpass-ok", "findpass-question-encoded",
            "findpass-disabled-user");

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
    private static Path uploadDir;

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
        uploadDir = Files.createTempDirectory("maccms-b09-upload");
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
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        if (uploadDir != null) {
            try (var s = Files.walk(uploadDir)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).map(Path::toFile).forEach(File::delete);
            }
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
    void accountCasesMatchPhpGolden() throws Exception {
        for (String version : List.of(GOLDEN80, GOLDEN73)) {
            int compared = 0;
            for (JsonNode c : MemberGolden.load(version).get("cases")) {
                String label = c.get("label").asString();
                if (label.startsWith("popedom-")) {
                    continue;
                }
                assertThat(c.has("error")).as(version + " " + label + " PHP 侧没有出错").isFalse();
                replay(version, c);
                compared++;
            }
            assertThat(compared).as(version).isGreaterThanOrEqualTo(90);
        }
    }

    private void replay(String version, JsonNode c) throws Exception {
        String label = c.get("label").asString();
        String what = version + " " + label;
        JsonNode args = c.get("args");
        prepare(args);
        MemberAccountService service = service(settings(args.get("config")));
        Map<String, PhpInput.Value> params = params(args.get("params"));
        String captcha = args.has("captcha") ? args.get("captcha").asString() : null;
        BooleanSupplier verify = () -> captcha != null && captcha.equalsIgnoreCase(text(params.get("verify")));
        int phpCode = c.get("code").asInt();
        String phpMsg = MemberGolden.utf8(c.get("msg"));
        String op = args.get("op").asString();
        switch (op) {
            case "register" -> {
                AccountReply r = service.register(params, Map.of(), verify, CLIENT_IP);
                if (JAVA_CODE.containsKey(label)) {
                    assertThat(phpCode).as(what + "（PHP 成功）").isEqualTo(1);
                    assertThat(r.code()).as(what + "（有意差异）").isEqualTo(JAVA_CODE.get(label));
                    break;
                }
                if (phpCode == 1) {
                    // 旧控制器注册成功后立即登录（index/controller/User.php:361-364）
                    boolean active = "1".equals(rowText(c, args, "user_status"));
                    assertThat(r.code()).as(what).isEqualTo(active ? 1 : 1003);
                    assertThat(r.msg()).as(what).isEqualTo(active ? "注册成功，登录成功" : "注册成功，获取用户信息失败");
                } else {
                    assertThat(r.code()).as(what).isEqualTo(phpCode);
                    assertThat(r.msg()).as(what).isEqualTo(phpMsg);
                }
                compareRows(what, c, Set.of("user_id", "user_random", "user_login_num", "user_login_ip", "user_end_time",
                        label.equals("reg-inviter") ? "user_points" : "-"));
            }
            case "login" -> {
                AccountReply r = service.login(params, verify, CLIENT_IP);
                if (JAVA_CODE.containsKey(label)) {
                    assertThat(phpCode).as(what + "（PHP 成功）").isEqualTo(1);
                    assertThat(r.code()).as(what + "（有意差异）").isEqualTo(JAVA_CODE.get(label));
                    assertThat(currentRandom(args)).as(what + " 失败时不写库").isEqualTo(seededRandom(args));
                    break;
                }
                assertThat(r.code()).as(what).isEqualTo(phpCode);
                assertThat(r.msg()).as(what).isEqualTo(phpMsg);
                compareRows(what, c, Set.of("user_random", "user_end_time"));
                if (phpCode == 1) {
                    assertThat(currentRandom(args)).as(what + " 登录换 user_random").isNotEqualTo(seededRandom(args));
                    compareLoginCookies(what, c.get("set_cookies"), r.setCookies());
                } else {
                    assertThat(currentRandom(args)).isEqualTo(seededRandom(args));
                }
            }
            case "info" -> {
                long id = args.get("as_user").asLong();
                MemberState member = sessions.resolve(cookiesFor(id));
                assertThat(member.member()).as(what + " 已登录").isTrue();
                AccountReply r = service.updateProfile(member, params);
                assertThat(r.ok()).as(what).isEqualTo(phpCode == 1);
                assertThat(r.msg()).as(what).isEqualTo(phpMsg);
                compareRows(what, c, Set.of("user_random"));
                boolean rotated = !currentRandom(args).equals(seededRandom(args));
                assertThat(rotated).as(what + " 改密时轮换 user_random（D2 ⑥）").isEqualTo(ROTATES.contains(label));
                assertThat(r.setCookies().isEmpty()).as(what + " 改密时重新签发 cookie").isEqualTo(!ROTATES.contains(label));
            }
            case "findpass" -> {
                AccountReply r = service.findpass(params, verify);
                assertThat(r.code()).as(what).isEqualTo(phpCode);
                assertThat(r.msg()).as(what).isEqualTo(phpMsg);
                compareRows(what, c, Set.of("user_random"));
                boolean rotated = !currentRandom(args).equals(seededRandom(args));
                assertThat(rotated).as(what + " 重置时轮换 user_random（D2 ⑥）").isEqualTo(ROTATES.contains(label));
            }
            case "regcheck" -> {
                String body = service.regcheck(params, s -> captcha != null && captcha.equalsIgnoreCase(s));
                JsonNode php = c.get("body");
                assertThat(body).as(what).isEqualTo(php == null || php.isNull() ? null : MemberGolden.utf8(php));
            }
            case "reg_msg", "findpass_msg" -> {
                AccountReply r = service.sendMessage(params, op.equals("reg_msg") ? 3 : 2);
                assertThat(r.code()).as(what).isEqualTo(phpCode);
                assertThat(r.msg()).as(what).isEqualTo(phpMsg);
            }
            case "findpass_reset" -> {
                AccountReply r = service.findpassReset(params);
                assertThat(r.code()).as(what).isEqualTo(phpCode);
                assertThat(r.msg()).as(what).isEqualTo(phpMsg);
            }
            default -> fail("unknown op " + op);
        }
    }

    /** 黄金记下的行与当前库逐列比较（文本按字节）；{@code skip} 中的列不比较。 */
    private void compareRows(String what, JsonNode c, Set<String> skip) throws SQLException {
        JsonNode rows = c.get("rows");
        if (rows == null || rows.isNull()) {
            return;
        }
        for (var e : rows.properties()) {
            String name = new String(HexFormat.of().parseHex(e.getKey()), StandardCharsets.UTF_8);
            Map<String, byte[]> java = row(name);
            if (e.getValue().isNull()) {
                assertThat(java).as(what + " 行 " + name + " 不存在").isNull();
                continue;
            }
            assertThat(java).as(what + " 行 " + name + " 存在").isNotNull();
            for (var col : e.getValue().properties()) {
                if (skip.contains(col.getKey())) {
                    continue;
                }
                assertThat(new String(java.get(col.getKey()), StandardCharsets.UTF_8))
                        .as(what + " " + name + "." + col.getKey())
                        .isEqualTo(MemberGolden.utf8(col.getValue()));
            }
        }
    }

    /** 登录签发的 cookie：名与顺序相同；除 user_check（随机值）外值相同；有效期按写出时刻，不比较。 */
    private static void compareLoginCookies(String what, JsonNode php, List<String> java) {
        List<String> phpPairs = new ArrayList<>();
        for (JsonNode h : php) {
            phpPairs.add(pair(MemberGolden.latin1(h)));
        }
        List<String> javaPairs = java.stream().map(MemberAccountGoldenMySqlIT::pair).toList();
        assertThat(javaPairs.stream().map(p -> p.split("=", 2)[0]).toList()).as(what + " cookie 名")
                .isEqualTo(phpPairs.stream().map(p -> p.split("=", 2)[0]).toList());
        for (int i = 0; i < phpPairs.size(); i++) {
            if (!phpPairs.get(i).startsWith("user_check=")) {
                assertThat(javaPairs.get(i)).as(what + " cookie " + i).isEqualTo(phpPairs.get(i));
            }
        }
        assertThat(java.stream().filter(h -> h.startsWith("user_check=")).allMatch(h -> h.endsWith("; HttpOnly")))
                .as(what + " user_check 带 HttpOnly（D-B08-7）").isTrue();
    }

    private static String pair(String header) {
        return header.split(";", 2)[0];
    }

    // ------------------------------------------------------------------ ② D2 ①②⑥

    @Test
    @Order(2)
    void d2LogoutAndPasswordChangeRotateTheCookieAndHashOrOpenidCannotLogIn() throws Exception {
        MemberAccountService service = service(MemberSettings.from(null));
        seedUser(601, "d2member01", md5("d2pass"), 2, "seed601", "OPENIDD2");
        // ① 库内 md5 值本身不能当密码
        assertThat(service.login(form("user_name", "d2member01", "user_pwd", md5("d2pass")), () -> true, CLIENT_IP)
                .code()).isEqualTo(1003);
        // ② openid/col 不能登录（按缺少用户名或密码）
        assertThat(service.login(form("openid", "OPENIDD2", "col", "user_openid_qq"), () -> true, CLIENT_IP).code())
                .isEqualTo(1001);
        assertThat(service.login(form("user_name", "d2member01", "user_pwd", "d2pass", "openid", "OTHER", "col",
                "user_openid_qq"), () -> true, CLIENT_IP).code()).as("带 openid 不影响正常密码登录").isEqualTo(1);
        // ⑥ 注销后旧 cookie 立即失效（在所有设备上）
        Map<String, PhpCookies.Value> first = login(service, "d2member01", "d2pass");
        Map<String, PhpCookies.Value> second = login(service, "d2member01", "d2pass");
        assertThat(sessions.resolve(first).member()).as("后一次登录挤掉前一次（旧版同样如此）").isFalse();
        assertThat(sessions.resolve(second).member()).isTrue();
        assertThat(service.logout(second).setCookies()).isEqualTo(MemberCookies.logout());
        assertThat(sessions.resolve(second).member()).as("注销后旧 cookie 失效").isFalse();
        // ⑥ 改密：其他设备的 cookie 失效，本次响应签发的新 cookie 有效
        Map<String, PhpCookies.Value> device = login(service, "d2member01", "d2pass");
        MemberState member = sessions.resolve(device);
        AccountReply changed = service.updateProfile(member, form("user_pwd", "d2pass", "user_pwd1", "d2new1",
                "user_pwd2", "d2new1"));
        assertThat(changed.ok()).isTrue();
        assertThat(sessions.resolve(device).member()).as("改密后旧 cookie 失效").isFalse();
        Map<String, PhpCookies.Value> renewed = browser(changed.setCookies());
        assertThat(sessions.resolve(renewed).member()).as("改密响应签发的新 cookie 有效").isTrue();
        assertThat(service.login(form("user_name", "d2member01", "user_pwd", "d2new1"), () -> true, CLIENT_IP).code())
                .isEqualTo(1);
        // ⑥ 找回重置：重置前签发的 cookie 失效
        setQuestion(601, "q601", "a601");
        Map<String, PhpCookies.Value> beforeReset = login(service, "d2member01", "d2new1");
        assertThat(service.findpass(form("user_name", "d2member01", "user_question", "q601", "user_answer", "a601",
                "user_pwd", "d2new2", "user_pwd2", "d2new2", "verify", "1234"), () -> true).code()).isEqualTo(1);
        assertThat(sessions.resolve(beforeReset).member()).as("找回重置后旧 cookie 失效").isFalse();
        // 新 cookie 交给旧 PHP 的 label_user（与 PHP 校验互认）
        phpAccepts(browserHeader(login(service, "d2member01", "d2new2")), 601);
    }

    // ------------------------------------------------------------------ ③ 同名并发注册

    @Test
    @Order(3)
    void concurrentRegistrationOfTheSameNameInsertsOneRow() throws Exception {
        // 仓库值 reg_num=1：前面的用例已用同一 IP 注册过，这里关掉 IP 限额，单独验证判重
        MemberAccountService service = service(settings(Map.of()));
        for (int round = 0; round < 5; round++) {
            String name = "racename0" + round;
            var outcome = ConcurrentWrites.run(16, i -> service.register(form("user_name", i % 2 == 0 ? name
                    : name.toUpperCase(), "user_pwd", "secret" + i, "user_pwd2", "secret" + i), Map.of(), () -> false,
                    CLIENT_IP).code());
            assertThat(outcome.failures()).isEmpty();
            assertThat(outcome.count(1)).as("round " + round + " 只有一个成功").isEqualTo(1);
            assertThat(outcome.count(1005)).as("其余为用户名已被注册").isEqualTo(15);
            assertThat(countByName(name)).as("round " + round + " 只有一行").isEqualTo(1);
        }
        // IP 限额放在同一条语句内：reg_num=2 时 16 个不同用户名并发只成功 2 个
        MemberAccountService limited = service(settings(Map.of("user.reg_num", "2")));
        var outcome = ConcurrentWrites.run(16, i -> limited.register(form("user_name", "iplimit" + (100 + i),
                "user_pwd", "secret", "user_pwd2", "secret"), Map.of(), () -> false, "10.9.8.7").code());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(1)).isEqualTo(2);
        assertThat(outcome.count(1009)).isEqualTo(14);
    }

    // ------------------------------------------------------------------ ④ 到期降级与 cookie

    @Test
    @Order(4)
    void expiryDowngradeIsOneRowAndCookiesMatchPhpGolden() throws Exception {
        long now = Instant.now().getEpochSecond();
        seedUser(701, "expiry0701", md5("x"), 3, "r701", "");
        setEnd(701, now - 10);
        Map<String, PhpCookies.Value> cookies = cookiesFor(701);
        // 16 个并发请求：库里只降一次（单行语句只命中 1 次），每个请求都按 2 组
        var outcome = ConcurrentWrites.run(16, i -> sessions.resolvePage(cookies));
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).allMatch(p -> p.state().groupId() == 2 && p.state().member());
        assertThat(group(701)).isEqualTo(2);
        setGroup(701, 3);
        var direct = ConcurrentWrites.run(16, i -> writer.expireMember(701, Instant.now().getEpochSecond()));
        assertThat(direct.count(1)).as("expireMember 并发只命中 1 次").isEqualTo(1);
        assertThat(direct.count(0)).isEqualTo(15);
        // 续费与降级竞态：无论先后，续费后的会员都不会被降级
        for (int round = 0; round < 20; round++) {
            setGroup(701, 3);
            setEnd(701, Instant.now().getEpochSecond() - 5);
            var race = ConcurrentWrites.run(2, i -> {
                try {
                    if (i == 0) {
                        return writer.expireMember(701, Instant.now().getEpochSecond());
                    }
                    try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                            "UPDATE mac_user SET group_id = 3, user_end_time = ? WHERE user_id = 701")) {
                        ps.setLong(1, Instant.now().getEpochSecond() + 3600);
                        return ps.executeUpdate();
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(race.failures()).isEmpty();
            assertThat(group(701)).as("round " + round + " 续费后仍是 3 组").isEqualTo(3);
            PageMember after = sessions.resolvePage(cookies);
            assertThat(after.state().groupId()).isEqualTo(3);
            assertThat(after.setCookies()).as("未到期不重发组 cookie").isEmpty();
        }
        // 组 cookie 与 PHP 黄金逐字节相同（Max-Age 由写出时刻决定，不比较）
        setGroup(701, 3);
        setEnd(701, Instant.now().getEpochSecond() - 1);
        PageMember downgraded = sessions.resolvePage(cookies);
        assertThat(downgraded.setCookies()).hasSize(2);
        for (String version : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            for (JsonNode c : MemberGolden.load(version).get("label_user")) {
                if (c.get("label").asString().equals("expiry-5-g3--1")) {
                    List<String> php = new ArrayList<>();
                    c.get("set_cookies").forEach(h -> php.add(MemberGolden.latin1(h)));
                    assertThat(php).hasSize(2);
                    assertThat(downgraded.setCookies().stream().map(MemberAccountGoldenMySqlIT::withoutDates).toList())
                            .as(version).isEqualTo(php.stream().map(MemberAccountGoldenMySqlIT::withoutDates).toList());
                }
            }
        }
        // 游客回写：带了三个会员 cookie 但校验不通过 → 与 PHP 黄金 guest_reset 相同
        Map<String, PhpCookies.Value> forged = PhpCookies.parse("user_id=701; user_name=expiry0701; user_check="
                + UserCheck.of("wrong", "expiry0701", 701));
        PageMember guest = sessions.resolvePage(forged);
        assertThat(guest.state().member()).isFalse();
        for (String version : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            for (JsonNode c : MemberGolden.load(version).get("setcookie")) {
                if (c.has("label") && c.get("label").asString().equals("guest_reset")) {
                    List<String> php = new ArrayList<>();
                    c.get("set_cookies").forEach(h -> php.add(MemberGolden.latin1(h)));
                    // 7.3 的 urlencode 与 8.0 的 rawurlencode 对 "0" 与 "游客" 的结果相同
                    assertThat(guest.setCookies()).as(version).isEqualTo(php);
                }
            }
        }
        assertThat(sessions.resolvePage(PhpCookies.parse("user_id=0; user_name=x; user_check=y")).setCookies())
                .as("前置判断不通过时不写 cookie").isEmpty();
    }

    // ------------------------------------------------------------------ ⑥ 头像

    @Test
    @Order(6)
    void portraitIsReencodedAndRecorded() throws Exception {
        MemberAccountService service = service(MemberSettings.from(null));
        seedUser(801, "portrait01", md5("x"), 2, "r801", "");
        MemberState member = sessions.resolve(cookiesFor(801));
        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        PortraitReply ok = service.portrait(member, png.toByteArray(), "me.PNG", null);
        assertThat(ok.code()).isEqualTo(1);
        assertThat(ok.msg()).isEqualTo("文件上传成功！");
        assertThat(ok.file()).matches("/upload/user/1/801\\.jpg\\?[0-9]{4}");
        BufferedImage saved = ImageIO.read(uploadDir.resolve("user/1/801.jpg").toFile());
        assertThat(saved.getWidth()).isEqualTo(100);
        assertThat(saved.getHeight()).isEqualTo(100);
        assertThat(new String(row("portrait01").get("user_portrait"), StandardCharsets.UTF_8))
                .isEqualTo("upload/user/1/801.jpg");
        PortraitReply bad = service.portrait(member, "<?php echo 1;".getBytes(StandardCharsets.UTF_8), "a.jpg", null);
        assertThat(bad.code()).isZero();
        assertThat(bad.msg()).isEqualTo("非系统允许的上传格式！");
        assertThat(bad.file()).matches("/\\?[0-9]{4}");
        AccountReply login = service.login(form("user_name", "portrait01", "user_pwd", "x"), () -> true, CLIENT_IP);
        assertThat(login.setCookies().get(5)).startsWith("user_portrait=%2Fupload%2Fuser%2F1%2F801.jpg;");
    }

    // ------------------------------------------------------------------ ⑦ 回滚演练

    @Test
    @Order(7)
    void dmlRestoreBringsBackS0AndSchemaIsUnchanged() throws Exception {
        DatabaseSnapshot now = snapshot();
        SnapshotDiff diff = SnapshotDiff.between(s0, now);
        assertThat(diff.isEmpty()).as("本 IT 的写入确实改了数据").isFalse();
        List<DmlRestore.Statement> plan = DmlRestore.plan(s0, now);
        int applied;
        try (Connection c = admin(dbName)) {
            applied = DmlRestore.apply(c, plan);
        }
        System.out.println("[mysql-it] member account DML restore statements applied: " + applied);
        assertThat(applied).isEqualTo(plan.size());
        assertThat(SnapshotDiff.between(s0, snapshot()).isEmpty()).as("恢复后与 S0 逐行相同").isTrue();
        try (Connection c = admin(dbName)) {
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).as("表结构指纹不变")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static MemberAccountService service(MemberSettings settings) {
        return new MemberAccountService(mapper, writer, sessions, new PortraitStore(uploadDir.toString()), settings,
                Clock.systemUTC(), new SecureRandom(), "/");
    }

    /** 黄金用例的配置（{@code user.*}）→ 旧配置数组 → {@link MemberSettings}。 */
    private static MemberSettings settings(JsonNode config) {
        Map<String, String> m = new LinkedHashMap<>();
        config.properties().forEach(e -> m.put(e.getKey(), e.getValue().asString()));
        return settings(m);
    }

    private static MemberSettings settings(Map<String, String> config) {
        Map<String, String> all = new LinkedHashMap<>(Map.of("user.status", "1", "user.reg_open", "1",
                "user.reg_status", "1", "user.reg_verify", "0", "user.login_verify", "0", "user.reg_points", "10",
                "user.reg_num", "0", "user.filter_words", "admin,cao,sex,xxx", "user.portrait_status", "1",
                "user.portrait_size", "100x100"));
        all.putAll(config);
        Map<Object, PhpValue> user = new LinkedHashMap<>();
        all.forEach((k, v) -> {
            if (k.startsWith("user.")) {
                user.put(PhpArray.normalizeKey(k.substring(5)), new PhpValue.PhpString(v));
            }
        });
        return MemberSettings.from(new PhpArray(Map.of("user", new PhpArray(user))));
    }

    /** 黄金用例开始前：删掉 absent 的用户名，按 seed 写入（覆盖）会员行（只在一次性库上）。 */
    private static void prepare(JsonNode args) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = admin(dbName)) {
            if (args.has("absent")) {
                for (JsonNode n : args.get("absent")) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM mac_user WHERE user_name = ?")) {
                        ps.setString(1, MemberGolden.utf8(n));
                        ps.executeUpdate();
                    }
                }
            }
            if (args.has("seed")) {
                for (JsonNode s : args.get("seed")) {
                    List<String> cols = new ArrayList<>();
                    List<Object> vals = new ArrayList<>();
                    for (var e : s.properties()) {
                        String k = e.getKey();
                        if (k.equals("end_offset")) {
                            cols.add("user_end_time");
                            vals.add(now + e.getValue().asLong());
                        } else if (k.equals("reg_now")) {
                            cols.add("user_reg_time");
                            vals.add(now);
                        } else if (e.getValue().isString()) {
                            cols.add(k);
                            vals.add(MemberGolden.utf8(e.getValue()));
                        } else {
                            cols.add(k);
                            vals.add(e.getValue().asLong());
                        }
                    }
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM mac_user WHERE user_id = ?")) {
                        del.setLong(1, s.get("user_id").asLong());
                        del.executeUpdate();
                    }
                    String sql = "INSERT INTO mac_user (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", cols.stream().map(x -> "?").toList()) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < vals.size(); i++) {
                            ps.setObject(i + 1, vals.get(i));
                        }
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    private static String seededRandom(JsonNode args) {
        return MemberGolden.utf8(args.get("seed").get(0).get("user_random"));
    }

    private static String currentRandom(JsonNode args) throws SQLException {
        String name = MemberGolden.utf8(args.get("seed").get(0).get("user_name"));
        return new String(row(name).get("user_random"), StandardCharsets.UTF_8);
    }

    private static String rowText(JsonNode c, JsonNode args, String column) {
        JsonNode rows = c.get("rows");
        for (var e : rows.properties()) {
            if (!e.getValue().isNull()) {
                return MemberGolden.utf8(e.getValue().get(column));
            }
        }
        return "";
    }

    private static Map<String, byte[]> row(String name) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM mac_user WHERE user_name = BINARY ? ORDER BY user_id LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, byte[]> out = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    out.put(rs.getMetaData().getColumnLabel(i), rs.getString(i) == null ? new byte[0]
                            : rs.getString(i).getBytes(StandardCharsets.UTF_8));
                }
                return out;
            }
        }
    }

    private static Map<String, PhpInput.Value> params(JsonNode hexMap) {
        Map<String, PhpInput.Value> out = new LinkedHashMap<>();
        if (hexMap != null) {
            hexMap.properties().forEach(e -> out.put(e.getKey(), new PhpInput.Value(MemberGolden.bytes(e.getValue()),
                    false)));
        }
        return out;
    }

    private static Map<String, PhpInput.Value> form(String... kv) {
        Map<String, PhpInput.Value> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(kv[i], new PhpInput.Value(kv[i + 1].getBytes(StandardCharsets.UTF_8), false));
        }
        return out;
    }

    private static String text(PhpInput.Value v) {
        return v == null ? "" : v.text();
    }

    /** 浏览器收到 Set-Cookie 后回送的 cookie（只取 名=值，删除 cookie 不回送）。 */
    private static Map<String, PhpCookies.Value> browser(List<String> setCookies) {
        return PhpCookies.parse(browserHeader(setCookies));
    }

    private static String browserHeader(List<String> setCookies) {
        List<String> pairs = new ArrayList<>();
        for (String h : setCookies) {
            String p = pair(h);
            if (!p.endsWith("=deleted")) {
                pairs.add(p);
            }
        }
        return String.join("; ", pairs);
    }

    private static String browserHeader(Map<String, PhpCookies.Value> cookies) {
        List<String> pairs = new ArrayList<>();
        cookies.forEach((k, v) -> pairs.add(k + "=" + PhpCookies.rawUrlEncode(v.text())));
        return String.join("; ", pairs);
    }

    private static Map<String, PhpCookies.Value> login(MemberAccountService service, String name, String pwd) {
        AccountReply r = service.login(form("user_name", name, "user_pwd", pwd), () -> true, CLIENT_IP);
        assertThat(r.code()).as("login " + name).isEqualTo(1);
        return browser(r.setCookies());
    }

    /** 按库里当前的 user_random 拼出该会员的有效 cookie（测试准备）。 */
    private static Map<String, PhpCookies.Value> cookiesFor(long id) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                "SELECT user_name, user_random FROM mac_user WHERE user_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String name = rs.getString(1);
                return PhpCookies.parse("user_id=" + id + "; user_name=" + PhpCookies.rawUrlEncode(name)
                        + "; user_check=" + UserCheck.of(rs.getString(2), name, id));
            }
        }
    }

    private static void seedUser(long id, String name, String pwdHash, int group, String random, String openid)
            throws SQLException {
        try (Connection c = admin(dbName)) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM mac_user WHERE user_id = ?")) {
                del.setLong(1, id);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO mac_user (user_id, user_name, user_pwd, "
                    + "group_id, user_status, user_random, user_openid_qq) VALUES (?, ?, ?, ?, 1, ?, ?)")) {
                ps.setLong(1, id);
                ps.setString(2, name);
                ps.setString(3, pwdHash);
                ps.setInt(4, group);
                ps.setString(5, random);
                ps.setString(6, openid);
                ps.executeUpdate();
            }
        }
    }

    private static void setQuestion(long id, String q, String a) throws SQLException {
        update("UPDATE mac_user SET user_question = ?, user_answer = ? WHERE user_id = ?", q, a, id);
    }

    private static void setGroup(long id, int group) throws SQLException {
        update("UPDATE mac_user SET group_id = ? WHERE user_id = ?", group, id);
    }

    private static void setEnd(long id, long end) throws SQLException {
        update("UPDATE mac_user SET user_end_time = ? WHERE user_id = ?", end, id);
    }

    private static void update(String sql, Object... args) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static int group(long id) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_id FROM mac_user WHERE user_id = " + id)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long countByName(String name) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM mac_user WHERE user_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String withoutDates(String header) {
        return header.replaceAll("expires=[^;]+; Max-Age=[0-9]+", "expires=X; Max-Age=N");
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把 cookie 交给旧 PHP 的 {@code label_user}（{@code member_golden_child.php} 的 label_user 操作，连到本 IT 的一次性库）。
     * 需要 {@code MACCMS_IT_PHP_CGI}（一个或多个 php-cgi，路径分隔符隔开）与仓库根目录的 {@code vendor/}；未设置时本步跳过。
     */
    private static void phpAccepts(String cookieHeader, long userId) throws Exception {
        String list = System.getenv("MACCMS_IT_PHP_CGI");
        Assumptions.assumeTrue(list != null && !list.isBlank(), "MACCMS_IT_PHP_CGI 未设置，跳过 PHP 互认");
        Path child = Path.of("src", "test", "php", "member_golden_child.php").toAbsolutePath();
        for (String cgi : list.split(File.pathSeparator)) {
            Path work = Files.createTempDirectory("maccms-b09-php");
            Files.createDirectories(work.resolve("runtime"));
            try {
                java.net.URI u = java.net.URI.create(adminUrl.substring("jdbc:".length()));
                ProcessBuilder pb = new ProcessBuilder(cgi, "-d", "session.save_path=" + work.resolve("runtime"),
                        "-d", "display_errors=0", "-d", "error_reporting=0");
                pb.directory(Path.of("..").toAbsolutePath().normalize().toFile());
                Map<String, String> env = pb.environment();
                env.put("GOLDEN_OP", "label_user");
                env.put("GOLDEN_ARGS", "{\"row\":" + userId + "}");
                env.put("GOLDEN_RUNTIME_PATH", work.toString());
                env.put("GOLDEN_DB_HOST", u.getHost());
                env.put("GOLDEN_DB_PORT", Integer.toString(u.getPort() < 0 ? 3306 : u.getPort()));
                env.put("GOLDEN_DB_NAME", dbName);
                env.put("GOLDEN_DB_USER", adminUser);
                env.put("GOLDEN_DB_PASSWORD", adminPassword);
                env.put("REDIRECT_STATUS", "200");
                env.put("GATEWAY_INTERFACE", "CGI/1.1");
                env.put("SERVER_PROTOCOL", "HTTP/1.1");
                env.put("REQUEST_METHOD", "GET");
                env.put("SCRIPT_FILENAME", child.toString());
                env.put("SCRIPT_NAME", "/index.php");
                env.put("REQUEST_URI", "/index.php");
                env.put("HTTP_HOST", "www.example.test");
                env.put("REMOTE_ADDR", "127.0.0.1");
                env.put("HTTP_COOKIE", cookieHeader);
                pb.redirectErrorStream(false);
                Process p = pb.start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.getErrorStream().readAllBytes();
                p.waitFor();
                String body = out.substring(out.indexOf("\r\n\r\n") + 4);
                JsonNode r = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
                assertThat(r.get("member").asBoolean()).as(cgi + " 旧 label_user 认新版签发的 cookie").isTrue();
                assertThat(r.get("user_id").asLong()).isEqualTo(userId);
                System.out.println("[mysql-it] PHP label_user accepted the Java-issued cookie: " + cgi);
            } finally {
                try (var s = Files.walk(work)) {
                    s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).map(Path::toFile).forEach(File::delete);
                }
            }
        }
    }

    private static DatabaseSnapshot snapshot() throws SQLException {
        try (Connection c = admin(dbName)) {
            return DatabaseSnapshot.take(c, TABLES);
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

package com.maccms.backend.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.content.application.ContentProperties;
import com.maccms.backend.content.application.PlaybackProperties;
import com.maccms.backend.content.infrastructure.ContentLookupMapper;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberExpiryService;
import com.maccms.backend.member.application.MemberFinanceSettings;
import com.maccms.backend.member.application.MemberPurchaseService;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberSettings;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.JsonReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.RecordPage;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import com.maccms.backend.schema.SchemaFingerprint;
import com.maccms.backend.writedrill.ConcurrentWrites;
import com.maccms.backend.writedrill.DatabaseSnapshot;
import com.maccms.backend.writedrill.DmlRestore;
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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 会员权益与财务的真实 MySQL 对照（B10，./mvnw verify -Pmysql-it；docs/architecture/19-b10-member-finance.md 第 4、6、10 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 导入 install.sql、B06 合成内容 seed.sql 与黄金的会员组/积分设定 →
 * 表结构指纹与快照 S0 → 以可写数据源打开的应用上下文（正式登记表）取会员与内容的 Mapper →
 * ① 逐条复放 PHP 8.0 与 7.3 的财务黄金：每个用例先执行黄金记录的初始行（时间按本次运行平移），再调用新版服务，比较 JSON 与 7 张表的行；
 * 有意差异（19 文档第 9 节）按新版结果断言 → ② 并发重复操作 → ③ 故障注入与补偿 → ④ 到期批量降级 → ⑤ 对账 →
 * ⑥ 只用 DML 恢复到 S0、逐行 diff 为空、表结构指纹不变 → 删库并确认不存在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemberFinanceGoldenMySqlIT {

    private static final Pattern DB_NAME = Pattern.compile("maccms_it_[0-9]{14}_[0-9a-f]{8}");
    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Path SEED_SQL = Path.of("src", "test", "resources", "openapi", "provide", "seed.sql");
    private static final List<String> TABLES = List.of("mac_user", "mac_group", "mac_ulog", "mac_plog", "mac_card",
            "mac_cash", "mac_order", "mac_visit", "mac_vod", "mac_art");
    private static final Map<String, String> PKS = new LinkedHashMap<>();
    private static final String USER_COLUMNS = "user_id, group_id, user_name, user_points, user_points_froze, "
            + "user_end_time, user_pid, user_pid_2, user_pid_3";
    private static final Set<String> TIME_COLUMNS = Set.of("ulog_time", "plog_time", "cash_time", "cash_time_audit",
            "order_time", "order_pay_time", "visit_time", "card_use_time", "card_add_time", "user_end_time");
    private static final long WINDOW = 400L * 86_400;
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOLDEN80 = "member/finance-php80-golden.json";
    private static final String GOLDEN73 = "member/finance-php73-golden.json";

    static {
        PKS.put("mac_user", "user_id");
        PKS.put("mac_ulog", "ulog_id");
        PKS.put("mac_plog", "plog_id");
        PKS.put("mac_card", "card_id");
        PKS.put("mac_cash", "cash_id");
        PKS.put("mac_order", "order_id");
        PKS.put("mac_visit", "visit_id");
    }

    /** 与旧版有意不同的用例（19 文档第 9 节）→ 新版的 JSON；行为"没有写入"（与用例开始时相同）。 */
    private static final Map<String, String> JAVA_UNCHANGED = Map.of(
            "buy.loose.mid01", "{\"code\":2001,\"msg\":\"参数错误\"}",              // D-B10-1
            "buy.loose.type4.0", "{\"code\":2001,\"msg\":\"参数错误\"}",            // D-B10-1
            "buy.sid-overflow", "{\"code\":1004,\"msg\":\"保存失败!：\"}",           // D-B10-3（旧版 500 且已扣积分）
            "ulogdel.loose-type", "{\"code\":1001,\"msg\":\"参数错误\"}",           // D-B10-1
            "cash.points-overflow", "{\"code\":1004,\"msg\":\"保存失败!：\"}",       // D-B10-3（旧版 500）
            "order.text", "{\"code\":1001,\"msg\":\"参数错误\"}",                    // D-B10-1（旧版 500）
            "order.points-overflow", "{\"code\":1002,\"msg\":\"保存失败!：\"}",      // D-B10-3（旧版 500）
            "orderinfo.other-user", "{\"code\":1002,\"msg\":\"获取失败\"}");          // D-B10-11
    /** 只在页面层判断的用例（未登录），服务层不复放；SiteFinanceMvcTest 覆盖。 */
    private static final Set<String> SITE_ONLY = Set.of("buy.guest", "ulog.set.guest");

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
    private static ContentLookupMapper lookup;
    private static MemberSessionService sessions;
    private static MemberMapper memberMapper;
    private static MemberWriteMapper memberWriter;
    private static List<String> fixture;

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
        JsonNode golden = MemberGolden.load(GOLDEN80);
        fixture = new ArrayList<>();
        golden.get("fixture").forEach(n -> fixture.add(n.asString()));
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : statements(INSTALL_SQL)) {
                st.execute(sql);
            }
            schemaAtImport = SchemaFingerprint.of(c, dbName);
            for (String sql : statements(SEED_SQL)) {
                st.execute(sql);
            }
            for (String sql : fixture) {
                st.execute(sql);
            }
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
        lookup = app.getBean(ContentLookupMapper.class);
        sessions = app.getBean(MemberSessionService.class);
        memberMapper = app.getBean(MemberMapper.class);
        memberWriter = app.getBean(MemberWriteMapper.class);
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
    void financeCasesMatchPhpGolden() throws Exception {
        JsonNode g80 = MemberGolden.load(GOLDEN80);
        JsonNode g73 = MemberGolden.load(GOLDEN73);
        Map<String, JsonNode> by73 = new LinkedHashMap<>();
        g73.get("cases").forEach(c -> by73.put(c.get("id").asString(), c));
        for (JsonNode golden : List.of(g80, g73)) {
            int compared = 0;
            for (JsonNode c : golden.get("cases")) {
                String id = c.get("id").asString();
                if (SITE_ONLY.contains(id)) {
                    continue;
                }
                // PHP 8 抛类型错误（500）而 7.3 正常的用例：新版按 7.3 的结果（D-B10-8）
                JsonNode expected = c.get("status").asInt() >= 500 && by73.get(id).get("status").asInt() < 500
                        ? by73.get(id) : c;
                replay(golden.get("php").asString() + " " + id, c, expected);
                compared++;
            }
            System.out.println("[mysql-it] B10 finance golden " + golden.get("php").asString() + " cases compared: "
                    + compared);
            assertThat(compared).isGreaterThan(140);
        }
    }

    private void replay(String what, JsonNode c, JsonNode expected) throws Exception {
        String id = c.get("id").asString();
        long goldenNow = c.get("now").asLong();
        long now = Instant.now().getEpochSecond();
        try (Connection conn = admin(dbName); Statement st = conn.createStatement()) {
            for (JsonNode sql : c.get("setup")) {
                st.execute(shift(sql.asString(), goldenNow, now));
            }
        }
        Map<String, List<List<String>>> before = rows(now);
        JsonNode request = c.get("request");
        Map<String, PhpInput.Value> params = PhpInput.merge(PhpInput.parseQuery(request.get("query").asString()),
                PhpInput.parse(request.get("post").asString().getBytes(StandardCharsets.ISO_8859_1)), Map.of());
        Map<String, Object> config = config(c.get("config"));
        String path = request.get("path").asString();
        Reply reply = call(path, request, params, config);
        Map<String, List<List<String>>> after = rows(Instant.now().getEpochSecond());
        if (JAVA_UNCHANGED.containsKey(id)) {
            assertThat(reply.json()).as(what + " 新版 JSON（有意差异）").isEqualTo(JAVA_UNCHANGED.get(id));
            assertThat(after).as(what + " 新版不写库").isEqualTo(before);
            return;
        }
        String expectedBody = new String(Base64.getDecoder().decode(expected.get("body").asString()),
                StandardCharsets.UTF_8);
        compareBody(what, id, expectedBody, reply);
        Map<String, List<List<String>>> goldenRows = goldenRows(expected);
        if (id.equals("card.duplicate")) {
            // D-B10-14：旧版把同号同密的两张未用卡一起作废，新版只认领一张（card_id 最小）
            assertThat(after.get("mac_user")).isEqualTo(goldenRows.get("mac_user"));
            assertThat(cardUsed(3)).isTrue();
            assertThat(cardUsed(4)).as("新版不作废第二张").isFalse();
            return;
        }
        for (String table : PKS.keySet()) {
            assertThat(after.get(table)).as(what + " " + table).isEqualTo(goldenRows.get(table));
        }
        if (!reply.cookies().isEmpty() || path.equals("/user/upgrade.html")) {
            List<String> php = new ArrayList<>();
            expected.get("setCookies").forEach(n -> php.add(pair(n.asString())));
            List<String> java = reply.cookies().stream().map(MemberFinanceGoldenMySqlIT::pair).toList();
            // label_user 的到期重发在页面层（拦截器）先写出，服务层只给升级本身的两个
            assertThat(php.subList(Math.max(0, php.size() - java.size()), php.size())).as(what + " Set-Cookie")
                    .isEqualTo(java);
        }
    }

    private static void compareBody(String what, String id, String php, Reply reply) throws Exception {
        if (reply.json() == null) {
            return;
        }
        JsonNode p = JSON.readTree(php);
        JsonNode j = JSON.readTree(reply.json());
        if (id.startsWith("ulog.list.")) {
            // data（内容名称、链接、图片）由 site 组装，SiteFinanceMvcTest 对照；这里比较其余字段
            for (JsonNode item : p.get("list")) {
                ((ObjectNode) item).remove("data");
            }
            if (id.equals("ulog.list.limit-big")) {
                assertThat(j.get("limit").asInt()).as(what + " limit 上限 100（D-B10-6）").isEqualTo(100);
                ((ObjectNode) p).put("limit", 100);
                ((ObjectNode) p).put("pagecount", 1);
            }
        }
        if (id.startsWith("order.") && p.has("data")) {
            for (JsonNode n : List.of(p, j)) {
                ObjectNode data = (ObjectNode) n.get("data");
                assertThat(data.get("order_code").asString()).matches("PAY\\d{20}");
                data.put("order_code", "PAY");
                data.put("order_time", 0);
            }
        }
        assertThat(j).as(what + " JSON").isEqualTo(p);
        if (!id.startsWith("ulog.list.") && !id.startsWith("order.")) {
            assertThat(reply.json()).as(what + " JSON 字节").isEqualTo(php);
        }
    }

    /** 调用与旧入口对应的新版服务，返回 JSON 正文（没有 JSON 的入口为 null）与 Set-Cookie。 */
    private Reply call(String path, JsonNode request, Map<String, PhpInput.Value> params, Map<String, Object> config)
            throws SQLException {
        MemberFinanceSettings settings = financeSettings(config);
        ContentLookupService content = contentLookup(config);
        Clock clock = Clock.systemUTC();
        MemberRecordService records = new MemberRecordService(reader, writer, content, clock);
        MemberPurchaseService purchases = new MemberPurchaseService(reader, writer, content, settings, clock);
        MemberWalletService wallet = new MemberWalletService(reader, writer, settings, clock, new SecureRandom());
        MemberReferralService referral = new MemberReferralService(writer, settings, clock);
        MemberState member = request.get("as").isNull() ? null : state(request.get("as").asLong());
        return switch (path) {
            case "/user/ajax_buy_popedom.html" -> Reply.of(purchases.buy(member, params));
            case "/user/ajax_ulog.html" -> MemberRecordService.isSet(params) ? Reply.of(records.ulogSet(member, params))
                    : new Reply(ulogListJson(records.ulogJsonList(member, params), member), List.of());
            case "/user/ulog_del.html" -> Reply.of(records.ulogDelete(member, params));
            case "/user/plog_del.html" -> Reply.of(records.plogDelete(member, params));
            case "/user/cash.html" -> Reply.of(wallet.applyCash(member, params));
            case "/user/cash_del.html" -> Reply.of(wallet.deleteCash(member, params));
            case "/user/buy.html" -> Reply.of(MemberWalletService.isCard(params) ? wallet.useCard(member, params)
                    : wallet.createOrder(member, params));
            case "/user/upgrade.html" -> Reply.of(wallet.upgrade(member, params));
            case "/user/pay.html" -> new Reply(wallet.orderByCode(member, params).isPresent() ? null
                    : errorJson("获取失败"), List.of());
            case "/user/gopay.html" -> new Reply(errorJson(MemberWalletService.gopayMessage(params)), List.of());
            case "/user/order_info.html" -> new Reply(wallet.orderById(member, params).map(o -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", 1);
                body.put("msg", "获取成功");
                body.put("info", MemberWalletService.orderJson(o));
                return LegacyJson.encode(body);
            }).orElse("{\"code\":1002,\"msg\":\"获取失败\"}"), List.of());
            case "/user/visit.html" -> {
                byte[] referer = request.get("referer").isNull() ? null
                        : request.get("referer").asString().getBytes(StandardCharsets.ISO_8859_1);
                referral.visit(params, request.get("remote").asString(), referer);
                yield new Reply(null, List.of());
            }
            case "/user/reg.html" -> {
                MemberAccountService accounts = new MemberAccountService(memberMapper, memberWriter, sessions,
                        new PortraitStore(""), MemberSettings.from(userConfig(config)), clock, new SecureRandom(), "/",
                        referral::invitedRegistration);
                Map<String, PhpCookies.Value> cookies = request.get("cookie").isNull() ? Map.of()
                        : PhpCookies.parse(List.of(request.get("cookie").asString()));
                var r = accounts.register(params, cookies, () -> true, request.get("remote").asString());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", r.code());
                body.put("msg", r.msg());
                yield new Reply(LegacyJson.encode(body), List.of());
            }
            default -> throw new IllegalStateException("未覆盖的黄金路径 " + path);
        };
    }

    /** ThinkPHP {@code error($msg)} 在 ajax 请求下的 JSON（SiteMemberFinance.error）。 */
    private static String errorJson(String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("msg", msg);
        body.put("data", "");
        body.put("url", "");
        body.put("wait", 3);
        return LegacyJson.encode(body);
    }

    /** 列表 JSON 中服务层负责的部分（与 SiteMemberFinance.ulogList 相同，不含 data）。 */
    private static String ulogListJson(RecordPage<FinanceRecords.Ulog> page, MemberState member) {
        List<Object> list = new ArrayList<>();
        for (FinanceRecords.Ulog u : page.items()) {
            Map<String, Object> item = new LinkedHashMap<>(MemberRecordService.ulogJson(u));
            item.put("user_name", member.userName());
            list.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 1);
        body.put("msg", "数据列表");
        body.put("page", page.page());
        body.put("pagecount", page.pageCount());
        body.put("limit", page.limit());
        body.put("total", page.total());
        body.put("list", list);
        return LegacyJson.encode(body);
    }

    private record Reply(String json, List<String> cookies) {

        static Reply of(JsonReply r) {
            return new Reply(LegacyJson.encode(r.body()), r.setCookies());
        }
    }

    // ------------------------------------------------------------------ ② 并发重复操作

    @Test
    @Order(2)
    void concurrentDuplicateOperationsTakeEffectExactlyAsAllowed() throws Exception {
        Map<String, Object> base = config(JSON.createObjectNode());
        MemberFinanceSettings settings = financeSettings(base);
        Clock clock = Clock.systemUTC();
        ContentLookupService content = contentLookup(base);
        MemberRecordService records = new MemberRecordService(reader, writer, content, clock);
        MemberPurchaseService purchases = new MemberPurchaseService(reader, writer, content, settings, clock);
        MemberWalletService wallet = new MemberWalletService(reader, writer, settings, clock, new SecureRandom());
        MemberReferralService referral = new MemberReferralService(writer, settings, clock);

        // 同一会员并发购买同一数据：每轮只扣一次、只有一条记录与一条 type 8 流水（vod 1 整片 5 积分）
        for (int round = 0; round < 5; round++) {
            resetLedger(101, 1000);
            MemberState m = state(101);
            var out = ConcurrentWrites.run(16, i -> purchases.buy(m, form("mid", "1", "id", "1", "type", "4")).msg());
            assertThat(out.failures()).isEmpty();
            assertThat(out.count("保存成功!")).as("round " + round).isEqualTo(1);
            assertThat(out.count("您已经购买过此条数据，无需再次支付，请刷新页面重试！")).isEqualTo(15);
            assertThat(points(101)).isEqualTo(995);
            assertThat(count("SELECT COUNT(*) FROM mac_ulog WHERE user_id = 101")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM mac_plog WHERE user_id = 101 AND plog_type = 8")).isEqualTo(1);
        }
        // 积分只够 k 次的不同数据（按集收费，vod 1 每集 3 积分）：恰好 k 个成功，积分不为负、失败的不留认领
        Map<String, Object> episode = config(JSON.readTree("{\"user\":{\"vod_points_type\":\"0\"}}"));
        MemberPurchaseService perEpisode = new MemberPurchaseService(reader, writer, contentLookup(episode),
                financeSettings(episode), clock);
        resetLedger(101, 3 * 5 + 2);
        MemberState m = state(101);
        var episodes = ConcurrentWrites.run(16, i -> perEpisode.buy(m, form("mid", "1", "id", "1", "sid", "1",
                "nid", Integer.toString(i + 1), "type", "4")).code());
        assertThat(episodes.count(1)).isEqualTo(5);
        assertThat(episodes.count(2002)).isEqualTo(11);
        assertThat(points(101)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM mac_ulog WHERE user_id = 101")).isEqualTo(5);
        assertThat(count("SELECT COALESCE(SUM(plog_points), 0) FROM mac_plog WHERE user_id = 101 AND plog_type = 8"))
                .isEqualTo(15);

        // 同一张卡 16 个会员同时使用：恰好 1 个成功、积分只加一次
        resetCards();
        List<MemberState> users = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            seedUser(200 + i, "cardusr" + (200 + i), 0, 2, 0, 0);
            users.add(state(200 + i));
        }
        var cards = ConcurrentWrites.run(16, i -> wallet.useCard(users.get(i), form("flag", "card",
                "card_no", "CONCURRENT000001", "card_pwd", "PWD00001")).code());
        assertThat(cards.count(1)).isEqualTo(1);
        assertThat(cards.count(1002)).isEqualTo(15);
        assertThat(count("SELECT COALESCE(SUM(user_points), 0) FROM mac_user WHERE user_id BETWEEN 200 AND 215"))
                .isEqualTo(30);
        assertThat(count("SELECT COUNT(*) FROM mac_card WHERE card_use_status = 1 AND user_id BETWEEN 200 AND 215"))
                .isEqualTo(1);

        // 并发重复提现（每次 1 元 = 100 积分，积分够 5 次）：恰好 5 条记录，冻结 = Σ cash_points，积分不为负
        resetLedger(101, 500);
        var cash = ConcurrentWrites.run(16, i -> wallet.applyCash(m, form("cash_money", "1", "cash_bank_name", "b",
                "cash_bank_no", "1", "cash_payee_name", "p")).code());
        assertThat(cash.count(1)).isEqualTo(5);
        assertThat(cash.count(1007)).isEqualTo(11);
        assertThat(points(101)).isZero();
        assertThat(froze(101)).isEqualTo(500);
        assertThat(count("SELECT COUNT(*) FROM mac_cash WHERE user_id = 101 AND cash_status = 0")).isEqualTo(5);

        // 并发撤销同一笔未审核提现：只退回一次
        long cashId = count("SELECT MIN(cash_id) FROM mac_cash WHERE user_id = 101");
        var undo = ConcurrentWrites.run(16, i -> wallet.deleteCash(m, form("ids", Long.toString(cashId))).code());
        assertThat(undo.failures()).isEmpty();
        assertThat(points(101)).isEqualTo(100);
        assertThat(froze(101)).isEqualTo(400);
        assertThat(count("SELECT COUNT(*) FROM mac_cash WHERE user_id = 101")).isEqualTo(4);

        // 并发升级（月 20 积分，积分够 5 次）：恰好 5 次成功，到期时间 = 起点 + 5 个月，5 条 type 7 流水
        resetLedger(101, 100);
        long start = Instant.now().getEpochSecond() + 86_400 * 10;
        update("UPDATE mac_user SET group_id = 3, user_end_time = ? WHERE user_id = 101", start);
        MemberState vip = state(101);
        var up = ConcurrentWrites.run(16, i -> wallet.upgrade(vip, form("group_id", "3", "long", "month")).code());
        assertThat(up.count(1)).isEqualTo(5);
        assertThat(up.count(1005)).isEqualTo(11);
        assertThat(points(101)).isZero();
        assertThat(count("SELECT user_end_time FROM mac_user WHERE user_id = 101")).isEqualTo(start + 5L * 2_592_000);
        assertThat(count("SELECT COUNT(*) FROM mac_plog WHERE user_id = 101 AND plog_type = 7")).isEqualTo(5);

        // 同一邀请人被并发注册：加分 = 16 × invite_reg_points，16 条 type 2 流水
        resetLedger(101, 0);
        MemberAccountService accounts = new MemberAccountService(memberMapper, memberWriter, sessions,
                new PortraitStore(""), MemberSettings.from(userConfig(base)), clock, new SecureRandom(), "/",
                referral::invitedRegistration);
        var regs = ConcurrentWrites.run(16, i -> accounts.register(form("user_name", "invitee" + (1000 + i),
                "user_pwd", "pass1234", "user_pwd2", "pass1234", "uid", "101"), Map.of(), () -> true, "10.0.0."
                + (i + 1)).code());
        assertThat(regs.count(1)).isEqualTo(16);
        assertThat(points(101)).isEqualTo(16L * 10);
        assertThat(count("SELECT COUNT(*) FROM mac_plog WHERE user_id = 101 AND plog_type = 2")).isEqualTo(16);

        // 同一 IP 并发推广访问（每日 3 次）：恰好 3 条访问、3 次加分
        resetLedger(101, 0);
        Map<String, Object> three = config(JSON.readTree("{\"user\":{\"invite_visit_num\":\"3\"}}"));
        MemberReferralService visits = new MemberReferralService(writer, financeSettings(three), clock);
        var v = ConcurrentWrites.run(16, i -> visits.visit(form("uid", "101"), "10.9.9.9", null));
        assertThat(v.count(1)).isEqualTo(3);
        assertThat(v.count(102)).isEqualTo(13);
        assertThat(count("SELECT COUNT(*) FROM mac_visit WHERE user_id = 101")).isEqualTo(3);
        assertThat(points(101)).isEqualTo(3);

        // 并发收藏同一数据：1 行
        resetLedger(101, 0);
        var fav = ConcurrentWrites.run(16, i -> records.ulogSet(m, form("ac", "set", "mid", "1", "id", "2",
                "type", "2")).code());
        assertThat(fav.count(1)).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM mac_ulog WHERE user_id = 101 AND ulog_type = 2")).isEqualTo(1);
        assertLedgerConsistent();
    }

    // ------------------------------------------------------------------ ③ 故障注入与补偿

    @Test
    @Order(3)
    void failedStepsAreCompensatedAndNothingIsLeftHalfDone() throws Exception {
        Map<String, Object> base = config(JSON.createObjectNode());
        Clock clock = Clock.systemUTC();
        MemberWalletService wallet = new MemberWalletService(reader, writer, financeSettings(base), clock,
                new SecureRandom());
        // 卡密：加积分被严格模式拒绝（int unsigned 溢出）→ 卡按条件释放，积分不变
        resetCards();
        resetLedger(101, 4_294_967_290L);
        MemberState m = state(101);
        assertThat(wallet.useCard(m, form("flag", "card", "card_no", "CONCURRENT000001", "card_pwd", "PWD00001"))
                .code()).isEqualTo(1003);
        assertThat(count("SELECT card_use_status FROM mac_card WHERE card_no = 'CONCURRENT000001'")).isZero();
        assertThat(count("SELECT user_id FROM mac_card WHERE card_no = 'CONCURRENT000001'")).isZero();
        assertThat(points(101)).isEqualTo(4_294_967_290L);
        // 提现：记录被拒绝（cash_points 超出 smallint）→ 冻结撤销
        resetLedger(101, 100_000);
        assertThat(wallet.applyCash(m, form("cash_money", "700", "cash_bank_name", "b", "cash_bank_no", "1",
                "cash_payee_name", "p")).code()).isEqualTo(1004);
        assertThat(points(101)).isEqualTo(100_000);
        assertThat(froze(101)).isZero();
        assertThat(count("SELECT COUNT(*) FROM mac_cash WHERE user_id = 101")).isZero();
        // 升级：流水被拒绝（周价格为负，plog_points 为 unsigned）→ 撤销升级，组与到期时间恢复
        resetLedger(101, 50);
        update("UPDATE mac_group SET group_points_week = -5 WHERE group_id = 3");
        try {
            assertThat(wallet.upgrade(m, form("group_id", "3", "long", "week")).code()).isEqualTo(1009);
            assertThat(points(101)).isEqualTo(50);
            assertThat(count("SELECT group_id FROM mac_user WHERE user_id = 101")).isEqualTo(2);
            assertThat(count("SELECT user_end_time FROM mac_user WHERE user_id = 101")).isZero();
        } finally {
            update("UPDATE mac_group SET group_points_week = 5 WHERE group_id = 3");
        }
        assertLedgerConsistent();
    }

    // ------------------------------------------------------------------ ④ 到期批量降级

    @Test
    @Order(4)
    void expirySweepUsesLessOrEqualAndNeverDowngradesARenewedMember() throws Exception {
        MemberExpiryService expiry = new MemberExpiryService(writer, Clock.systemUTC(), null);
        long now = Instant.now().getEpochSecond();
        seedUser(301, "expire301", 0, 3, now - 10, 0);
        seedUser(302, "expire302", 0, 3, now + 3600, 0);
        seedUser(303, "expire303", 0, 2, now - 10, 0);
        seedUser(304, "expire304", 0, 9, now - 10, 0);
        expiry.expireAll();
        assertThat(group(301)).isEqualTo(2);
        assertThat(group(302)).isEqualTo(3);
        assertThat(group(303)).isEqualTo(2);
        assertThat(group(304)).as("组不存在也照样降级").isEqualTo(2);
        // 等于现在也降级（旧 User::expire 的 <=）：时钟固定在到期那一秒
        long edge = now + 5000;
        seedUser(305, "expire305", 0, 3, edge, 0);
        seedUser(306, "expire306", 0, 3, edge + 1, 0);
        new MemberExpiryService(writer, Clock.fixed(Instant.ofEpochSecond(edge), ZoneId.of("UTC")), null).expireAll();
        assertThat(group(305)).isEqualTo(2);
        assertThat(group(306)).isEqualTo(3);
        // 与续费并发：续费后的会员不被降级
        Map<String, Object> base = config(JSON.createObjectNode());
        MemberWalletService wallet = new MemberWalletService(reader, writer, financeSettings(base), Clock.systemUTC(),
                new SecureRandom());
        for (int round = 0; round < 20; round++) {
            seedUser(307, "renew307", 1000, 3, Instant.now().getEpochSecond() - 1, 0);
            MemberState m = new MemberState(true, 307, "renew307", 2, 3, null, 0, true);
            var out = ConcurrentWrites.run(2, i -> i == 0 ? wallet.upgrade(m, form("group_id", "3", "long", "day"))
                    .code() : expiry.expireAll());
            assertThat(out.failures()).isEmpty();
            assertThat(group(307)).as("round " + round).isEqualTo(3);
            assertThat(count("SELECT user_end_time FROM mac_user WHERE user_id = 307"))
                    .isGreaterThan(Instant.now().getEpochSecond());
        }
    }

    // ------------------------------------------------------------------ ⑤ 回滚演练

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
        System.out.println("[mysql-it] B10 finance DML restore statements applied: " + applied);
        assertThat(applied).isEqualTo(plan.size());
        try (Connection c = admin(dbName)) {
            assertThat(SnapshotDiff.between(s0, DatabaseSnapshot.take(c, TABLES)).isEmpty()).as("恢复后与 S0 逐行相同")
                    .isTrue();
            assertThat(SchemaFingerprint.diff(schemaAtImport, SchemaFingerprint.of(c, dbName))).as("表结构指纹不变")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------ 对账（19 文档第 6.5 节）

    /**
     * 对账会员（{@link #resetLedger} 写入的会员，起点积分记在内存中）：{@code user_points + user_points_froze = 起点 + Σ 卡密积分
     * + Σ 流水(1～6) − Σ 流水(7、8)}；{@code 冻结 = Σ 未审核提现}；购买记录的积分总和 = type 8 流水。
     */
    private static void assertLedgerConsistent() throws SQLException {
        for (Map.Entry<Long, Long> e : LEDGER_START.entrySet()) {
            long id = e.getKey();
            long expected = e.getValue()
                    + count("SELECT COALESCE(SUM(card_points), 0) FROM mac_card WHERE user_id = " + id
                    + " AND card_use_status = 1")
                    + count("SELECT COALESCE(SUM(plog_points), 0) FROM mac_plog WHERE user_id = " + id
                    + " AND plog_type BETWEEN 1 AND 6")
                    - count("SELECT COALESCE(SUM(plog_points), 0) FROM mac_plog WHERE user_id = " + id
                    + " AND plog_type IN (7, 8)");
            assertThat(points(id) + froze(id)).as("会员 " + id + " 积分账").isEqualTo(expected);
            assertThat(froze(id)).as("会员 " + id + " 冻结账").isEqualTo(count(
                    "SELECT COALESCE(SUM(cash_points), 0) FROM mac_cash WHERE user_id = " + id + " AND cash_status = 0"));
            assertThat(count("SELECT COALESCE(SUM(ulog_points), 0) FROM mac_ulog WHERE user_id = " + id
                    + " AND ulog_type IN (1, 4, 5)")).as("会员 " + id + " 购买账").isEqualTo(count(
                    "SELECT COALESCE(SUM(plog_points), 0) FROM mac_plog WHERE user_id = " + id + " AND plog_type = 8"));
        }
    }

    private static final Map<Long, Long> LEDGER_START = new LinkedHashMap<>();

    /** 对账会员重新开始：删掉他的记录、流水、提现与访问，积分设为 {@code points}，冻结 0，组 2。 */
    private static void resetLedger(long id, long points) throws SQLException {
        for (String t : List.of("mac_ulog", "mac_plog", "mac_cash", "mac_visit")) {
            update("DELETE FROM " + t + " WHERE user_id = ?", id);
        }
        update("UPDATE mac_card SET card_use_status = 0, card_sale_status = 0, user_id = 0, card_use_time = 0 "
                + "WHERE user_id = ?", id);
        update("DELETE FROM mac_user WHERE user_id = ? OR user_pid = ?", id, id);
        seedUser(id, "ledger" + id, points, 2, 0, 0);
        LEDGER_START.put(id, points);
    }

    private static void resetCards() throws SQLException {
        update("DELETE FROM mac_card WHERE card_no = 'CONCURRENT000001'");
        update("INSERT INTO mac_card (card_no, card_pwd, card_money, card_points, card_add_time) "
                + "VALUES ('CONCURRENT000001', 'PWD00001', 10, 30, 1700000000)");
    }

    // ------------------------------------------------------------------ 辅助

    private static MemberState state(long userId) throws SQLException {
        String random;
        String name;
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                "SELECT user_random, user_name FROM mac_user WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    fail("会员不存在 " + userId);
                }
                random = rs.getString(1);
                name = rs.getString(2);
            }
        }
        String header = "user_id=" + userId + "; user_name=" + name + "; user_check="
                + md5(random + "-" + name + "-" + userId + "-");
        MemberState s = sessions.resolvePage(PhpCookies.parse(List.of(header))).state();
        assertThat(s.member()).as("会员 " + userId + " 已登录").isTrue();
        return s;
    }

    private static void seedUser(long id, String name, long points, int group, long end, long pid)
            throws SQLException {
        update("DELETE FROM mac_user WHERE user_id = ?", id);
        update("INSERT INTO mac_user (user_id, group_id, user_name, user_pwd, user_status, user_random, user_points, "
                + "user_end_time, user_pid, user_question, user_answer) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, '', '')",
                id, group, name, md5("secret"), "rnd" + id, points, end, pid);
    }

    private static long points(long id) throws SQLException {
        return count("SELECT user_points FROM mac_user WHERE user_id = " + id);
    }

    private static long froze(long id) throws SQLException {
        return count("SELECT user_points_froze FROM mac_user WHERE user_id = " + id);
    }

    private static int group(long id) throws SQLException {
        return (int) count("SELECT group_id FROM mac_user WHERE user_id = " + id);
    }

    private static boolean cardUsed(long id) throws SQLException {
        return count("SELECT card_use_status FROM mac_card WHERE card_id = " + id) == 1;
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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

    /** 7 张表的全部行（会员只取本批会改的列），去掉主键、时间按"相对现在的分钟数"表示，排序后比较。 */
    private static Map<String, List<List<String>>> rows(long now) throws SQLException {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> e : PKS.entrySet()) {
                String cols = e.getKey().equals("mac_user") ? USER_COLUMNS : "*";
                List<List<String>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT " + cols + " FROM " + e.getKey())) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String col = md.getColumnLabel(i);
                            if (col.equals(e.getValue())) {
                                continue;
                            }
                            row.add(col + "=" + normalize(col, rs.getString(i), now));
                        }
                        rows.add(row);
                    }
                }
                rows.sort((a, b) -> String.join("|", a).compareTo(String.join("|", b)));
                out.put(e.getKey(), rows);
            }
        }
        return out;
    }

    private static Map<String, List<List<String>>> goldenRows(JsonNode c) {
        long now = c.get("now").asLong();
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : PKS.entrySet()) {
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode r : c.get("rows").get(e.getKey())) {
                List<String> row = new ArrayList<>();
                for (Map.Entry<String, JsonNode> col : r.properties()) {
                    if (col.getKey().equals(e.getValue())) {
                        continue;
                    }
                    row.add(col.getKey() + "=" + normalize(col.getKey(), col.getValue().isNull() ? null
                            : col.getValue().asString(), now));
                }
                rows.add(row);
            }
            rows.sort((a, b) -> String.join("|", a).compareTo(String.join("|", b)));
            out.put(e.getKey(), rows);
        }
        return out;
    }

    private static String normalize(String col, String v, long now) {
        if (v != null && col.equals("order_code") && v.matches("PAY\\d{20}")) {
            // 单号 = PAY + 下单时刻（PRC）+ 6 位随机数，两边各自生成
            return "PAY*";
        }
        if (v == null || !TIME_COLUMNS.contains(col) || !v.matches("\\d{1,10}")) {
            return v;
        }
        long t = Long.parseLong(v);
        if (t == LocalDate.ofInstant(Instant.ofEpochSecond(now), PRC).atStartOfDay(PRC).toEpochSecond() - 1) {
            // 黄金的"今天零点前一秒"（昨天的推广访问）
            return "TODAY-1";
        }
        long offset = t - now;
        return Math.abs(offset) <= WINDOW ? "T" + Math.round(offset / 60.0) : v;
    }

    /** 黄金初始行里相对黄金生成时刻的时间平移到本次运行（"今天零点前一秒"按今天零点平移）。 */
    private static String shift(String sql, long goldenNow, long now) {
        long goldenToday = LocalDate.ofInstant(Instant.ofEpochSecond(goldenNow), PRC).atStartOfDay(PRC).toEpochSecond();
        long today = LocalDate.ofInstant(Instant.ofEpochSecond(now), PRC).atStartOfDay(PRC).toEpochSecond();
        Matcher m = Pattern.compile("(?<![\\d'])(\\d{10})(?![\\d'])").matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            long v = n;
            if (n == goldenToday - 1) {
                v = today - 1;
            } else if (Math.abs(n - goldenNow) <= WINDOW) {
                v = now + (n - goldenNow);
            }
            m.appendReplacement(sb, Long.toString(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 黄金的基础配置与用例覆盖合并后的 {@code user}、{@code pay} 段（键 → 原文）。 */
    private static Map<String, Object> config(JsonNode override) {
        JsonNode base = MemberGolden.load(GOLDEN80).get("baseConfig");
        Map<String, Object> out = new TreeMap<>();
        for (String section : List.of("user", "pay")) {
            Map<String, String> m = new LinkedHashMap<>();
            base.get(section).properties().forEach(e -> m.put(e.getKey(), e.getValue().asString()));
            if (override != null && override.has(section)) {
                override.get(section).properties().forEach(e -> m.put(e.getKey(), e.getValue().asString()));
            }
            out.put(section, m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static PhpArray phpArray(Map<String, Object> config) {
        Map<Object, PhpValue> root = new LinkedHashMap<>();
        for (Map.Entry<String, Object> section : config.entrySet()) {
            Map<Object, PhpValue> s = new LinkedHashMap<>();
            ((Map<String, String>) section.getValue()).forEach((k, v) -> s.put(PhpArray.normalizeKey(k),
                    new PhpValue.PhpString(v)));
            root.put(section.getKey(), new PhpArray(s));
        }
        return new PhpArray(root);
    }

    private static MemberFinanceSettings financeSettings(Map<String, Object> config) {
        return MemberFinanceSettings.from(phpArray(config));
    }

    private static PhpArray userConfig(Map<String, Object> config) {
        return phpArray(config);
    }

    @SuppressWarnings("unchecked")
    private static ContentLookupService contentLookup(Map<String, Object> config) {
        Map<String, String> user = (Map<String, String>) config.get("user");
        return new ContentLookupService(lookup, new ContentProperties(true, false, null,
                user.getOrDefault("art_points_type", "1")),
                new PlaybackProperties(user.getOrDefault("vod_points_type", "1"), "0", null));
    }

    private static Map<String, PhpInput.Value> form(String... kv) {
        Map<String, PhpInput.Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new PhpInput.Value(kv[i + 1].getBytes(StandardCharsets.UTF_8), false));
        }
        return m;
    }

    /** {@code Set-Cookie} 的"名=值"（不比较有效期：PHP 与新版各按自己的请求时间）。 */
    private static String pair(String header) {
        int semi = header.indexOf(';');
        return semi < 0 ? header : header.substring(0, semi);
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

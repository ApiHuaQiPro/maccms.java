package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.content.application.ContentPurchases;
import com.maccms.backend.content.application.view.CategoryNode;
import com.maccms.backend.content.application.view.ContentTitle;
import com.maccms.backend.member.MemberGolden;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberCookies;
import com.maccms.backend.member.application.MemberFinanceSettings;
import com.maccms.backend.member.application.MemberPurchaseService;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.JsonReply;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.application.view.RecordPage;
import com.maccms.backend.site.config.SiteConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * B10 会员权益与财务入口的 HTTP 契约（19 文档第 2～4、8 节）：未登录与功能关闭、JSON 的字节与键顺序（含 {@code ajax_ulog} 列表的
 * {@code data} 与 PHP 8.0 黄金逐字节相同）、非 ajax 时的提示页、各页面的结构与 DOM 钩子、未配置支付（不生成网关地址、不跳转）、
 * 二维码、推广访问的跳转。业务规则由 MemberFinanceGoldenMySqlIT 对照 PHP 黄金；这里的服务都是替身。
 */
@WebMvcTest(controllers = {SiteUserController.class, SitePlaceholderController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.legacy-config.dir=../application"})
@Import({SiteConfiguration.class, ClientIpConfiguration.class})
class SiteFinanceMvcTest {

    private static final String AJAX = "XMLHttpRequest";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    MemberAccountService accounts;
    @MockitoBean
    MemberSessionService sessions;
    @MockitoBean
    MemberRecordService records;
    @MockitoBean
    MemberPurchaseService purchases;
    @MockitoBean
    MemberWalletService wallet;
    @MockitoBean
    MemberReferralService referral;
    @MockitoBean
    ContentLookupService content;

    private static final MemberGroup DEFAULT = new MemberGroup(2, "默认会员", 1, ",1,",
            "{\"1\":{\"1\":\"1\",\"2\":\"2\",\"3\":\"3\"}}");
    private static final MemberState MEMBER = new MemberState(true, 7, "buyer007", 2, 2, DEFAULT, 0, false);
    private static final MemberProfile PROFILE = new MemberProfile(7, "buyer007", "", 2, "默认会员", 0, 100, "", "",
            "", "2026-01-01 00:00:00", "", "", "/static/images/touxiang.png", "", "", "", "");

    @BeforeEach
    void stubs() {
        when(accounts.available()).thenReturn(true);
        when(accounts.pageSettings()).thenReturn(new MemberPageSettings(true, false, false, true, false, false, true));
        when(accounts.profile(any())).thenReturn(PROFILE);
        when(records.available()).thenReturn(true);
        when(records.purchases(anyLong())).thenReturn(ContentPurchases.NONE);
        when(purchases.available()).thenReturn(true);
        when(wallet.available()).thenReturn(true);
        when(referral.available()).thenReturn(true);
        when(wallet.settings()).thenReturn(MemberFinanceSettings.REPOSITORY);
        when(wallet.wallet(anyLong())).thenReturn(Optional.of(new FinanceRecords.Wallet(7, "buyer007", 2, 100, 30, 0)));
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MEMBER, List.of(), true));
    }

    private void guest() {
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Document html(MvcResult r) throws Exception {
        return Jsoup.parse(body(r));
    }

    // ------------------------------------------------------------------ 未登录、功能关闭

    @Test
    void guestsGetNotLoggedInAndVisitStaysPublic() throws Exception {
        guest();
        for (String path : List.of("/index.php/user/buy.html", "/index.php/user/plays.html",
                "/index.php/user/ajax_ulog.html?ac=set&mid=1&id=1&type=2", "/index.php/user/cash_del.html")) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(body(r)).as(path).contains("未登录", "data-site-page=\"user-jump\"");
        }
        assertThat(body(mvc.perform(post("/index.php/user/ajax_buy_popedom.html").header("X-Requested-With", AJAX))
                .andReturn())).isEqualTo("{\"code\":0,\"msg\":\"未登录\",\"data\":\"\",\"url\":\"\\/index.php\\/user\\/login.html\",\"wait\":3}");
        verify(purchases, never()).buy(any(), any());
        MvcResult visit = mvc.perform(get("/index.php/user/visit.html?uid=7")).andReturn();
        assertThat(visit.getResponse().getStatus()).isEqualTo(302);
        assertThat(visit.getResponse().getHeader("Location")).isEqualTo("/");
        verify(referral).visit(any(), eq("127.0.0.1"), any());
    }

    @Test
    void withoutTheWriteDatabaseFinanceActionsAre503() throws Exception {
        when(wallet.available()).thenReturn(false);
        MvcResult r = mvc.perform(get("/index.php/user/orders.html").header("X-Requested-With", AJAX)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(body(r)).isEqualTo("{\"code\":503,\"msg\":\"会员功能暂未开放\"}");
        assertThat(mvc.perform(get("/index.php/user/visit.html?uid=7")).andReturn().getResponse().getStatus())
                .isEqualTo(503);
    }

    // ------------------------------------------------------------------ ajax_ulog 列表：与 PHP 8.0 黄金逐字节相同

    @Test
    void ulogListJsonMatchesPhpGoldenIncludingContentData() throws Exception {
        stubTitles();
        JsonNode golden = MemberGolden.load("member/finance-php80-golden.json");
        int compared = 0;
        for (JsonNode c : golden.get("cases")) {
            String id = c.get("id").asString();
            if (!id.equals("ulog.list.all") && !id.equals("ulog.list.type2-page2")
                    && !id.equals("ulog.list.missing-content")) {
                continue;
            }
            String php = new String(Base64.getDecoder().decode(c.get("body").asString()), StandardCharsets.UTF_8);
            JsonNode expected = tools.jackson.databind.json.JsonMapper.builder().build().readTree(php);
            List<FinanceRecords.Ulog> rows = new ArrayList<>();
            for (JsonNode u : expected.get("list")) {
                rows.add(new FinanceRecords.Ulog(u.get("ulog_id").asLong(), u.get("user_id").asLong(),
                        u.get("ulog_mid").asInt(), u.get("ulog_type").asInt(), u.get("ulog_rid").asLong(),
                        u.get("ulog_sid").asInt(), u.get("ulog_nid").asInt(), u.get("ulog_points").asInt(),
                        u.get("ulog_time").asLong()));
            }
            when(records.ulogJsonList(any(), any())).thenReturn(new RecordPage<>(rows, expected.get("total").asLong(),
                    expected.get("page").asInt(), expected.get("limit").asInt()));
            MvcResult r = mvc.perform(get("/index.php/user/ajax_ulog.html?ac=list").header("X-Requested-With", AJAX))
                    .andReturn();
            assertThat(r.getResponse().getContentType()).isEqualTo("application/json;charset=UTF-8");
            assertThat(body(r)).as(id).isEqualTo(php);
            compared++;
        }
        assertThat(compared).isEqualTo(3);
    }

    /** 黄金种子（openapi/provide/seed.sql 与 finance_golden.php 的设定）里被记录引用的内容。 */
    private void stubTitles() {
        when(content.titles(anyInt(), anyCollection())).thenAnswer(inv -> {
            int mid = inv.getArgument(0);
            Map<Long, ContentTitle> m = new LinkedHashMap<>();
            switch (mid) {
                case 1 -> {
                    m.put(1L, new ContentTitle(1, "星际穿越", "mac://img.example.test/vod/1.jpg", true, 2, "动作片"));
                    m.put(3L, new ContentTitle(3, "长篇剧集", "http://img.example.test/3.jpg", true, 4, "电视剧"));
                    m.put(5L, new ContentTitle(5, "未审核视频", "", true, 2, "动作片"));
                }
                case 2 -> m.put(1L, new ContentTitle(1, "新闻一", "mac://img.example.test/art/1.jpg", true, 21, "新闻"));
                case 3 -> m.put(1L, new ContentTitle(1, "专题甲", "upload/topic/1.jpg", false, 0, ""));
                case 8 -> m.put(1L, new ContentTitle(1, "演员甲", "upload/actor/1.jpg", false, 0, ""));
                default -> {
                }
            }
            return m;
        });
    }

    // ------------------------------------------------------------------ 内容页的表单：非 ajax 为提示页

    @Test
    void contentFormsWithoutAjaxGetAJumpPageBackToTheContent() throws Exception {
        when(records.ulogSet(any(), any())).thenReturn(JsonReply.of(1, "保存成功!"));
        MvcResult fav = mvc.perform(post("/index.php/user/ajax_ulog.html").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("ac=set&mid=1&id=9&type=2")).andReturn();
        assertThat(fav.getResponse().getStatus()).isEqualTo(200);
        Document page = html(fav);
        assertThat(page.selectFirst(".jump-page__message").text()).isEqualTo("保存成功!");
        assertThat(page.selectFirst(".jump-page__message").attr("role")).isEqualTo("status");
        assertThat(page.selectFirst(".jump-page__link").attr("href")).isEqualTo("/index.php/vod/detail/id/9.html");
        assertThat(fav.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'none'");

        when(purchases.buy(any(), any())).thenReturn(JsonReply.of(2002,
                "对不起,查看此页面数据需要[5]积分，您还剩下[4]积分，请先充值！"));
        MvcResult buy = mvc.perform(post("/index.php/user/ajax_buy_popedom.html")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("mid=1&id=9&sid=1&nid=2&type=4")).andReturn();
        Document b = html(buy);
        assertThat(b.selectFirst(".jump-page__message").attr("role")).isEqualTo("alert");
        assertThat(b.selectFirst(".jump-page__link").attr("href")).isEqualTo("/index.php/vod/play/id/9/sid/1/nid/2.html");

        when(purchases.buy(any(), any())).thenReturn(JsonReply.of(1002, "获取失败").wrapped());
        MvcResult ajax = mvc.perform(get("/index.php/user/ajax_buy_popedom.html?mid=1&id=999&type=4")
                .header("X-Requested-With", AJAX)).andReturn();
        assertThat(body(ajax)).as("旧版 json([$res])").isEqualTo("[{\"code\":1002,\"msg\":\"获取失败\"}]");
        MvcResult artBuy = mvc.perform(post("/index.php/user/ajax_buy_popedom.html")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("mid=2&id=100&sid=3&nid=0&type=1")).andReturn();
        assertThat(html(artBuy).selectFirst(".jump-page__link").attr("href")).isEqualTo("/index.php/art/detail/id/100/page/3.html");
    }

    // ------------------------------------------------------------------ 充值、支付（未配置）、二维码

    @Test
    void buyPageAndUnconfiguredPaymentNeverLeaveTheSite() throws Exception {
        MvcResult page = mvc.perform(get("/index.php/user/buy.html")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'nonce-");
        Document d = html(page);
        Element card = d.selectFirst("form[data-member-form=buy_card]");
        assertThat(card.attr("action")).isEqualTo("/index.php/user/buy.html");
        assertThat(card.attr("data-success-url")).isEqualTo("/index.php/user/buy.html");
        assertThat(card.selectFirst("input[name=flag]").val()).isEqualTo("card");
        assertThat(card.select("input[name=card_no], input[name=card_pwd], [data-member-message]")).hasSize(3);
        Element order = d.selectFirst("form[data-member-form=buy_order]");
        assertThat(order.attr("data-success-url")).isEqualTo("/index.php/user/orders.html");
        assertThat(order.selectFirst("input[name=price]")).isNotNull();
        assertThat(body(page)).contains("最小充值金额 10 元").doesNotContain("http://", "https://");

        when(wallet.createOrder(any(), any())).thenReturn(JsonReply.of(orderBody()));
        MvcResult created = mvc.perform(post("/index.php/user/buy.html").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("price=10").header("X-Requested-With", AJAX)).andReturn();
        assertThat(body(created)).isEqualTo("{\"code\":1,\"msg\":\"保存成功!\",\"data\":{\"user_id\":7,"
                + "\"order_code\":\"PAY20261025103224858712\",\"order_price\":\"10\",\"order_time\":1792895544,"
                + "\"order_points\":10}}");

        FinanceRecords.Order o = new FinanceRecords.Order(5, 7, 0, "PAY20261025103224858712", "10.00", 1792895544L, 10,
                "", 0, "");
        when(wallet.orderByCode(any(), any())).thenReturn(Optional.of(o));
        MvcResult pay = mvc.perform(get("/index.php/user/pay.html?order_code=PAY20261025103224858712")).andReturn();
        Document p = html(pay);
        assertThat(p.select("form.pay-method")).hasSize(4);
        assertThat(p.select("form.pay-method input[name=payment]")).extracting(Element::val)
                .containsExactly("alipay", "codepay", "weixin", "zhapay");
        for (Element f : p.select("form.pay-method")) {
            assertThat(f.attr("method")).isEqualTo("get");
            assertThat(f.attr("action")).isEqualTo("/index.php/user/gopay.html");
            assertThat(f.selectFirst("input[name=order_code]").val()).isEqualTo("PAY20261025103224858712");
            assertThat(f.selectFirst("input[name=order_id]").val()).isEqualTo("5");
        }
        assertThat(body(pay)).doesNotContain("http://", "https://", "alipay.com", "mapi.");

        MvcResult go = mvc.perform(get("/index.php/user/gopay.html?order_code=PAY20261025103224858712&order_id=5"
                + "&payment=alipay")).andReturn();
        assertThat(go.getResponse().getStatus()).as("不跳转").isEqualTo(200);
        assertThat(go.getResponse().getHeader("Location")).isNull();
        assertThat(html(go).selectFirst(".jump-page__message").text()).isEqualTo("该支付选项未开启！");
        assertThat(body(go)).doesNotContain("http://", "https://");
        assertThat(body(mvc.perform(get("/index.php/user/gopay.html?payment=weixin").header("X-Requested-With", AJAX))
                .andReturn())).isEqualTo("{\"code\":0,\"msg\":\"该支付选项未开启！\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
        assertThat(body(mvc.perform(get("/index.php/user/gopay.html").header("X-Requested-With", AJAX)).andReturn()))
                .isEqualTo("{\"code\":0,\"msg\":\"参数错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}");

        when(wallet.orderByCode(any(), any())).thenReturn(Optional.empty());
        assertThat(body(mvc.perform(get("/index.php/user/pay.html?order_code=NOPE").header("X-Requested-With", AJAX))
                .andReturn())).isEqualTo("{\"code\":0,\"msg\":\"获取失败\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
    }

    private static Map<String, Object> orderBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", 7L);
        data.put("order_code", "PAY20261025103224858712");
        data.put("order_price", "10");
        data.put("order_time", 1792895544L);
        data.put("order_points", 10L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 1);
        body.put("msg", "保存成功!");
        body.put("data", data);
        return body;
    }

    @Test
    void weixinQrcodeIsALocalPngOfTheSameText() throws Exception {
        String data = "weixin://wxpay/bizpayurl?pr=SYNTH123";
        MvcResult r = mvc.perform(get(java.net.URI.create("/index.php/user/qrcode.html?data=" + enc(data)))).andReturn();
        assertThat(r.getResponse().getContentType()).isEqualTo("image/png");
        var image = ImageIO.read(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(w, h, pixels)))).getText();
        assertThat(decoded).isEqualTo(data);
        MvcResult bad = mvc.perform(get(java.net.URI.create("/index.php/user/qrcode.html?data=" + enc("https://evil.example/")))).andReturn();
        assertThat(html(bad).selectFirst(".jump-page__message").text()).isEqualTo("参数错误");
    }

    // ------------------------------------------------------------------ 升级、列表页、订单、推广

    @Test
    void upgradeAnswersJsonWithGroupCookies() throws Exception {
        List<String> cookies = MemberCookies.groupRefresh(3, "VIP会员", 1_792_895_544L);
        when(wallet.upgrade(any(), any())).thenReturn(JsonReply.of(1, "升级会员组成功").withCookies(cookies));
        MvcResult r = mvc.perform(post("/index.php/user/upgrade.html").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("group_id=3&long=month").header("X-Requested-With", AJAX)).andReturn();
        assertThat(body(r)).isEqualTo("{\"code\":1,\"msg\":\"升级会员组成功\"}");
        assertThat(r.getResponse().getHeaders("Set-Cookie")).extracting(h -> h.split(";", 2)[0])
                .containsExactly("group_id=3", "group_name=VIP%E4%BC%9A%E5%91%98");
        when(wallet.groups()).thenReturn(List.of(new FinanceRecords.GroupOffer(1, "游客", 1, 0, 0, 0, 0),
                new FinanceRecords.GroupOffer(3, "VIP会员", 1, 1, 5, 20, 200),
                new FinanceRecords.GroupOffer(4, "关闭的组", 0, 1, 1, 1, 1)));
        Document page = html(mvc.perform(get("/index.php/user/upgrade.html")).andReturn());
        assertThat(page.select("input[name=group_id]")).extracting(Element::val).containsExactly("3");
        assertThat(page.select("input[name=long]")).extracting(Element::val).containsExactly("day", "week", "month", "year");
        assertThat(page.selectFirst("form[data-member-form=upgrade]").attr("data-success-url"))
                .isEqualTo("/index.php/user/upgrade.html");
    }

    @Test
    void recordListsHaveDeleteFormsFiltersAndPagination() throws Exception {
        stubTitles();
        List<FinanceRecords.Ulog> rows = List.of(new FinanceRecords.Ulog(11, 7, 1, 4, 1, 1, 2, 0, 1700000020),
                new FinanceRecords.Ulog(12, 7, 1, 4, 999, 1, 1, 3, 1700000010));
        when(records.plays(any(), any())).thenReturn(new RecordPage<>(rows, 45, 2, 20));
        Document d = html(mvc.perform(get("/index.php/user/plays/page/2.html")).andReturn());
        assertThat(d.selectFirst("[data-site-page=user-plays]")).isNotNull();
        assertThat(d.select("table.member-table tbody tr")).hasSize(2);
        assertThat(d.selectFirst("tbody tr a").attr("href")).isEqualTo("/index.php/vod/play/id/1/sid/1/nid/2.html");
        assertThat(d.select("tbody tr").get(1).text()).contains("内容已删除");
        Element del = d.selectFirst("tbody form[data-member-form=delete]");
        assertThat(del.attr("action")).isEqualTo("/index.php/user/ulog_del.html");
        assertThat(del.attr("data-success-url")).isEqualTo("/index.php/user/plays/page/2.html");
        assertThat(del.select("input[type=hidden]")).extracting(e -> e.attr("name") + "=" + e.val())
                .containsExactly("ids=11", "type=4");
        Element clear = d.selectFirst(".member-page__actions form[data-member-form=clear]");
        assertThat(clear.select("input[type=hidden]")).extracting(e -> e.attr("name") + "=" + e.val())
                .containsExactly("all=1", "type=4");
        assertThat(d.selectFirst(".pagination__link--prev").attr("href")).isEqualTo("/index.php/user/plays.html");
        assertThat(d.selectFirst(".pagination__link--next").attr("href")).isEqualTo("/index.php/user/plays/page/3.html");

        when(records.favs(any(), any())).thenReturn(new RecordPage<>(List.of(), 0, 1, 20));
        Document favs = html(mvc.perform(get("/index.php/user/favs/mid/2.html")).andReturn());
        assertThat(favs.select(".member-tabs__link")).extracting(Element::text)
                .containsExactly("全部", "视频", "文章", "专题", "演员");
        assertThat(favs.selectFirst(".member-tabs__link--current").attr("href")).isEqualTo("/index.php/user/favs/mid/2.html");
        assertThat(favs.selectFirst(".member-empty").text()).isEqualTo("暂无记录。");

        when(wallet.orders(any(), any())).thenReturn(new RecordPage<>(List.of(new FinanceRecords.Order(5, 7, 0,
                "PAY20261025103224858712", "10.00", 1700000000L, 10, "", 0, "")), 1, 1, 20));
        Document orders = html(mvc.perform(get("/index.php/user/orders.html")).andReturn());
        assertThat(orders.select("tbody a")).extracting(e -> e.attr("href")).containsExactly(
                "/index.php/user/order_info/order_id/5.html",
                "/index.php/user/pay.html?order_code=PAY20261025103224858712");

        when(wallet.invitees(any(), any())).thenReturn(new RecordPage<>(List.of(new FinanceRecords.Invitee(9,
                "newuser01", "", 1700000000L, 0)), 1, 1, 20));
        Document reward = html(mvc.perform(get("/index.php/user/reward/level/2.html")).andReturn());
        assertThat(reward.selectFirst(".member-tabs__link--current").text()).isEqualTo("二级推广");
        assertThat(reward.text()).contains("推广注册链接：http://maccms.local.test/index.php/user/reg/uid/7.html",
                "推广访问链接：http://maccms.local.test/index.php/user/visit/uid/7.html", "newuser01");
    }

    @Test
    void orderInfoIsJsonForAjaxAndAPageOtherwise() throws Exception {
        FinanceRecords.Order o = new FinanceRecords.Order(1, 7, 0, "PAY20260101000000123456", "10.00", 1700000000L, 10, "",
                0, "");
        when(wallet.orderById(any(), any())).thenReturn(Optional.of(o));
        assertThat(body(mvc.perform(get("/index.php/user/order_info.html?order_id=1").header("X-Requested-With", AJAX))
                .andReturn())).isEqualTo("{\"code\":1,\"msg\":\"获取成功\",\"info\":{\"order_id\":1,\"user_id\":7,"
                + "\"order_status\":0,\"order_code\":\"PAY20260101000000123456\",\"order_price\":\"10.00\","
                + "\"order_time\":1700000000,\"order_points\":10,\"order_pay_type\":\"\",\"order_pay_time\":0,"
                + "\"order_remarks\":\"\"}}");
        Document page = html(mvc.perform(get("/index.php/user/order_info/order_id/1.html")).andReturn());
        assertThat(page.selectFirst("[data-site-page=user-order]")).isNotNull();
        assertThat(page.text()).contains("PAY20260101000000123456", "未支付", "去支付");
        when(wallet.orderById(any(), any())).thenReturn(Optional.empty());
        assertThat(body(mvc.perform(get("/index.php/user/order_info.html?order_id=2").header("X-Requested-With", AJAX))
                .andReturn())).isEqualTo("{\"code\":1002,\"msg\":\"获取失败\"}");
    }

    @Test
    void visitRedirectsOnlyToTheSameHost() throws Exception {
        assertThat(mvc.perform(get(java.net.URI.create("/index.php/user/visit.html?uid=7&url="
                + enc("http://maccms.local.test/index.php/vod/detail/id/1.html"))).header("Host", "maccms.local.test"))
                .andReturn().getResponse().getHeader("Location"))
                .isEqualTo("http://maccms.local.test/index.php/vod/detail/id/1.html");
        for (String url : List.of("http://evil.example.test/", "/index.php", "javascript://maccms.local.test/x",
                "http://maccms.local.test:8080/x", "http://maccms.local.test/a\r\nX: y")) {
            assertThat(mvc.perform(get(java.net.URI.create("/index.php/user/visit.html?uid=7&url=" + enc(url)))
                    .header("Host", "maccms.local.test")).andReturn().getResponse().getHeader("Location")).as(url)
                    .isEqualTo("/");
        }
    }

    @Test
    void memberCenterLinksAndPermissionsPage() throws Exception {
        Document index = html(mvc.perform(get("/index.php/user/index.html")).andReturn());
        assertThat(index.select(".member-nav--finance a")).extracting(e -> e.attr("href")).contains(
                "/index.php/user/buy.html", "/index.php/user/upgrade.html", "/index.php/user/popedom.html",
                "/index.php/user/favs.html", "/index.php/user/plays.html", "/index.php/user/downs.html",
                "/index.php/user/plog.html", "/index.php/user/cash.html", "/index.php/user/reward.html",
                "/index.php/user/orders.html", "/index.php/user/cards.html");
        when(content.typeTree()).thenReturn(List.of(new CategoryNode(1, "电影", 1,
                List.of(new CategoryNode(6, "动作片", 1, List.of()))), new CategoryNode(2, "资讯", 2, List.of())));
        Document popedom = html(mvc.perform(get("/index.php/user/popedom.html")).andReturn());
        var rows = popedom.select("tbody tr");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).select("td")).extracting(Element::text).containsExactly("是", "是", "是", "否", "否");
        assertThat(rows.get(1).selectFirst("th").hasClass("member-table__child")).isTrue();
        assertThat(rows.get(2).select("td")).extracting(Element::text).containsExactly("否", "否", "", "", "");

        when(wallet.cashes(any(), any())).thenReturn(new RecordPage<>(List.of(new FinanceRecords.Cash(1, 0, 20, "0.20",
                "b", "1", "a", 1700000001L, 0)), 1, 1, 20));
        Document cash = html(mvc.perform(get("/index.php/user/cash.html")).andReturn());
        assertThat(cash.selectFirst("form[data-member-form=cash]").select("input[name]")).extracting(e -> e.attr("name"))
                .containsExactly("cash_money", "cash_bank_name", "cash_bank_no", "cash_payee_name");
        assertThat(cash.selectFirst("tbody form[data-member-form=delete] button").text()).isEqualTo("撤销");
        assertThat(cash.text()).contains("冻结积分", "30");
    }
}

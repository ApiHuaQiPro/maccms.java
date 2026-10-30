package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.content.application.ContentPurchases;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberFinanceSettings;
import com.maccms.backend.member.application.MemberPaymentService;
import com.maccms.backend.member.application.MemberPurchaseService;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.application.view.PayCallback;
import com.maccms.backend.member.application.view.PaymentContext;
import com.maccms.backend.member.application.view.PaymentStep;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 在线支付的页面与回调入口（B24，33 文档第 6、7、9 节）：{@code gopay} 的四种输出（302 到网关、支付宝表单、微信二维码页、提示页/空页面）、
 * 内容安全策略只为支付宝网关放行 {@code form-action}、页面除 W3 网关地址外没有外部主机；回调 {@code payment/notify} 的 GET 提示、POST 的
 * 原文回复与参数解析（GET → POST → 路由）、其他操作 404、可写数据源关闭时 503。支付规则由 PaymentGoldenTest / PaymentGoldenMySqlIT 对照
 * PHP 黄金；这里的服务都是替身。
 */
@WebMvcTest(controllers = {SiteUserController.class, SitePaymentController.class, SitePlaceholderController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.legacy-config.dir=php/application"})
@Import({SiteConfiguration.class, ClientIpConfiguration.class})
class SitePaymentMvcTest {

    private static final Pattern ABSOLUTE = Pattern.compile("(?i)(?:https?:)?//[A-Za-z0-9.-]+");

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
    @MockitoBean
    MemberPaymentService payments;

    private static final MemberGroup DEFAULT = new MemberGroup(2, "默认会员", 1, ",1,", "{}");
    private static final MemberState MEMBER = new MemberState(true, 7, "buyer007", 2, 2, DEFAULT, 0, false);
    private static final FinanceRecords.Order ORDER = new FinanceRecords.Order(5, 7, 0, "PAY20261029120000123456",
            "20.00", 1792895544L, 20, "", 0, "");

    @BeforeEach
    void stubs() {
        when(accounts.available()).thenReturn(true);
        when(accounts.pageSettings()).thenReturn(new MemberPageSettings(true, false, false, true, false, false, true));
        when(records.available()).thenReturn(true);
        when(records.purchases(org.mockito.ArgumentMatchers.anyLong())).thenReturn(ContentPurchases.NONE);
        when(purchases.available()).thenReturn(true);
        when(wallet.available()).thenReturn(true);
        when(referral.available()).thenReturn(true);
        when(payments.available()).thenReturn(true);
        when(wallet.settings()).thenReturn(MemberFinanceSettings.REPOSITORY);
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MEMBER, List.of(), true));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private PaymentContext gopay(PaymentStep step) throws Exception {
        ArgumentCaptor<PaymentContext> ctx = ArgumentCaptor.forClass(PaymentContext.class);
        when(payments.gopay(any(), any(), ctx.capture())).thenReturn(step);
        mvc.perform(get("/index.php/user/gopay.html?order_id=5&order_code=X&payment=p")).andReturn();
        return ctx.getValue();
    }

    @Test
    void gopayPassesTheRequestContext() throws Exception {
        PaymentContext ctx = gopay(new PaymentStep.Message("该支付选项未开启！"));
        assertThat(ctx.clientIp()).isEqualTo("127.0.0.1");
        assertThat(ctx.w3()).as("W3 默认打开（B24 起）").isTrue();
        assertThat(ctx.notifyUrl().apply("alipay")).as("回调地址按 base-url，不看 Host")
                .isEqualTo("http://maccms.local.test/index.php/payment/notify/pay_type/alipay");
    }

    @Test
    void messagesAndBlankFollowLegacy() throws Exception {
        when(payments.gopay(any(), any(), any())).thenReturn(new PaymentStep.Message("获取单据失败"));
        MvcResult page = mvc.perform(get("/index.php/user/gopay.html?payment=alipay")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(Jsoup.parse(body(page)).selectFirst(".jump-page__message").text()).isEqualTo("获取单据失败");
        assertThat(body(mvc.perform(get("/index.php/user/gopay.html?payment=alipay").header("X-Requested-With",
                "XMLHttpRequest")).andReturn())).isEqualTo("{\"code\":0,\"msg\":\"获取单据失败\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
        when(payments.gopay(any(), any(), any())).thenReturn(new PaymentStep.Blank());
        MvcResult blank = mvc.perform(get("/index.php/user/gopay.html?payment=min")).andReturn();
        assertThat(blank.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(blank)).isEmpty();
    }

    @Test
    void codepayAndZhapayRedirectToTheGateway() throws Exception {
        String url = "https://api.xiuxiu888.com/creat_order/?debug=1&id=10001&sign=abc";
        when(payments.gopay(any(), any(), any())).thenReturn(new PaymentStep.Redirect(url));
        MvcResult r = mvc.perform(get("/index.php/user/gopay.html?payment=codepay")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getHeader("Location")).isEqualTo(url);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(body(r)).isEmpty();
    }

    @Test
    void alipayFormPostsToTheGatewayOnlyAndCspAllowsJustThatOrigin() throws Exception {
        String action = "https://mapi.alipay.com/gateway.do?_input_charset=utf-8";
        when(payments.gopay(any(), any(), any())).thenReturn(new PaymentStep.AutoPost(action, List.of(
                new PaymentStep.Field("_input_charset", "utf-8"), new PaymentStep.Field("subject", "积分充值（UID：）"),
                new PaymentStep.Field("notify_url", "http://maccms.local.test/index.php/payment/notify/pay_type/alipay"),
                new PaymentStep.Field("sign", "b07120c498f1527bc0ac9a1f4e78ec53"),
                new PaymentStep.Field("sign_type", "MD5"))));
        MvcResult r = mvc.perform(get("/index.php/user/gopay.html?payment=alipay")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("form-action 'self' https://mapi.alipay.com;").contains("script-src 'nonce-");
        Document d = Jsoup.parse(body(r));
        Element form = d.selectFirst("form#alipaysubmit");
        assertThat(form.attr("method")).isEqualTo("post");
        assertThat(form.attr("action")).isEqualTo(action);
        assertThat(form.hasAttr("data-pay-autosubmit")).isTrue();
        List<String> fields = form.select("input[type=hidden]").stream().map(e -> e.attr("name") + "=" + e.val()).toList();
        assertThat(fields).containsExactly("_input_charset=utf-8", "subject=积分充值（UID：）",
                "notify_url=http://maccms.local.test/index.php/payment/notify/pay_type/alipay",
                "sign=b07120c498f1527bc0ac9a1f4e78ec53", "sign_type=MD5");
        assertThat(form.selectFirst("button[type=submit]").text()).isEqualTo("正在提交");
        // 页面上的外部主机只有 W3 网关（隐藏字段里的回调地址是本站）
        assertThat(externalHosts(body(r))).containsOnly("mapi.alipay.com");
    }

    @Test
    void weixinShowsALocalQrcodeOrTheFailure() throws Exception {
        when(payments.gopay(any(), any(), any())).thenReturn(new PaymentStep.Weixin(ORDER,
                Optional.of("weixin://wxpay/bizpayurl?pr=TestOnly1")));
        MvcResult r = mvc.perform(get("/index.php/user/gopay.html?payment=weixin")).andReturn();
        Document d = Jsoup.parse(body(r));
        assertThat(d.selectFirst("[data-site-page=user-pay-weixin]")).isNotNull();
        assertThat(d.selectFirst("img[alt=微信支付二维码]").attr("src"))
                .isEqualTo("/index.php/user/qrcode.html?data=weixin%3A%2F%2Fwxpay%2Fbizpayurl%3Fpr%3DTestOnly1");
        assertThat(d.select(".member-summary dd").eachText()).contains("PAY20261029120000123456", "20.00");
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("form-action 'self';");
        assertThat(externalHosts(body(r))).as("微信二维码在本站生成，无外部主机").isEmpty();

        when(payments.gopay(any(), any(), any())).thenReturn(new PaymentStep.Weixin(ORDER, Optional.empty()));
        Document failed = Jsoup.parse(body(mvc.perform(get("/index.php/user/gopay.html?payment=weixin")).andReturn()));
        assertThat(failed.selectFirst(".notice--error").text()).isEqualTo("获取微信二维码失败");
        assertThat(failed.selectFirst("img[alt=微信支付二维码]")).isNull();
    }

    @Test
    void withoutThePaymentServiceGopayKeepsTheB10Answer() throws Exception {
        when(payments.available()).thenReturn(false);
        assertThat(body(mvc.perform(get("/index.php/user/gopay.html?payment=alipay&order_id=1").header("X-Requested-With",
                "XMLHttpRequest")).andReturn())).isEqualTo("{\"code\":0,\"msg\":\"该支付选项未开启！\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
        verify(payments, never()).gopay(any(), any(), any());
    }

    // ------------------------------------------------------------------ payment/notify

    @Test
    void notifyGetIsThePaidPage() throws Exception {
        MvcResult page = mvc.perform(get("/index.php/payment/notify/pay_type/alipay.html?out_trade_no=X")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        Document d = Jsoup.parse(body(page));
        assertThat(d.selectFirst(".jump-page__message").text()).isEqualTo("支付完成！");
        assertThat(d.select("a").eachAttr("href")).contains("/index.php/user/index.html");
        assertThat(body(mvc.perform(get("/index.php/payment/notify/pay_type/alipay").header("X-Requested-With",
                "XMLHttpRequest")).andReturn())).isEqualTo(
                "{\"code\":1,\"msg\":\"支付完成！\",\"data\":\"\",\"url\":\"\\/index.php\\/user\\/index.html\",\"wait\":3}");
        verify(payments, never()).notify(any());
    }

    @Test
    void notifyPostPassesInputPostAndRawBodyAndAnswersVerbatim() throws Exception {
        ArgumentCaptor<PayCallback> cb = ArgumentCaptor.forClass(PayCallback.class);
        when(payments.notify(cb.capture())).thenReturn("success2");
        MvcResult r = mvc.perform(post("/index.php/payment/notify/pay_type/alipay?q=1&trade_status=GET")
                .contentType("application/x-www-form-urlencoded").content("trade_status=TRADE_SUCCESS&pay_type=x&a%5B%5D=1"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(body(r)).isEqualTo("success2");
        PayCallback c = cb.getValue();
        assertThat(c.input().get("pay_type").text()).as("路由参数覆盖 POST").isEqualTo("alipay");
        assertThat(c.input().get("trade_status").text()).as("POST 覆盖 GET").isEqualTo("TRADE_SUCCESS");
        assertThat(c.input().get("q").text()).isEqualTo("1");
        assertThat(c.input().get("a").array()).isTrue();
        assertThat(c.post()).containsKeys("trade_status", "pay_type", "a").doesNotContainKey("q");
        assertThat(new String(c.raw(), StandardCharsets.UTF_8)).isEqualTo("trade_status=TRADE_SUCCESS&pay_type=x&a%5B%5D=1");

        String xml = "<xml><return_code><![CDATA[SUCCESS]]></return_code></xml>";
        when(payments.notify(cb.capture())).thenReturn("<xml>reply</xml>");
        MvcResult w = mvc.perform(post("/index.php/payment/notify/pay_type/weixin").contentType("text/xml").content(xml))
                .andReturn();
        assertThat(body(w)).isEqualTo("<xml>reply</xml>");
        assertThat(cb.getValue().post()).as("非表单正文没有 $_POST").isEmpty();
        assertThat(new String(cb.getValue().raw(), StandardCharsets.UTF_8)).isEqualTo(xml);
    }

    @Test
    void otherPaymentActionsAre404AndNoWriteDatabaseIs503() throws Exception {
        assertThat(mvc.perform(post("/index.php/payment/index")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/index.php/payment")).andReturn().getResponse().getStatus()).isEqualTo(404);
        when(payments.available()).thenReturn(false);
        MvcResult r = mvc.perform(post("/index.php/payment/notify/pay_type/codepay")
                .contentType("application/x-www-form-urlencoded").content("pay_id=1")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        verify(payments, never()).notify(any());
    }

    private static List<String> externalHosts(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = ABSOLUTE.matcher(html);
        while (m.find()) {
            String host = m.group().replaceFirst("(?i)^(?:https?:)?//", "").toLowerCase(java.util.Locale.ROOT);
            if (!host.equals("maccms.local.test") && !host.equals("www.w3.org")) {
                out.add(host);
            }
        }
        return out;
    }
}

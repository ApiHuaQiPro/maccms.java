package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.ContentViewer;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberCookies;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.application.view.PortraitReply;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B09 会员入口的 HTTP 契约（18 文档第 2、3、4、5、8 节）：地址与方法、按字节读取的参数、JSON 的字节与键顺序、跳转、cookie、
 * 未登录与功能关闭、页面的 CSP nonce 与 DOM 钩子、{@code label_user} 接线（游客回写、观看者会员组、缓存头）。业务规则由
 * MemberAccountGoldenMySqlIT 对照 PHP 黄金；这里的服务都是替身。
 */
@WebMvcTest(controllers = {SiteUserController.class, SitePlaceholderController.class, SiteVerifyController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.legacy-config.dir=../application"})
@Import({SiteConfiguration.class, ClientIpConfiguration.class})
class SiteUserMvcTest {

    private static final String LOGIN = "/index.php/user/login.html";
    private static final Pattern NONCE = Pattern.compile("script-src 'nonce-([A-Za-z0-9+/]{22}==)'");

    @Autowired
    MockMvc mvc;
    @MockitoBean
    MemberAccountService accounts;
    @MockitoBean
    MemberSessionService sessions;

    private static final MemberGroup VIP = new MemberGroup(3, "VIP会员", 1, ",1,", "{}");
    private static final MemberState MEMBER = new MemberState(true, 7, "member07", 3, 3, VIP, 0, false);
    private static final MemberProfile PROFILE = new MemberProfile(7, "member07", "昵称&amp;", 3, "VIP会员", 0, 12,
            "", "", "123", "2026-01-01 00:00:00", "", "", "/upload/user/7/7.jpg", "昵称&", "123", "问&题", "答案");

    @BeforeEach
    void stubs() {
        when(accounts.available()).thenReturn(true);
        when(accounts.pageSettings()).thenReturn(new MemberPageSettings(true, true, false, true, false, false, true));
        when(accounts.profile(any())).thenReturn(PROFILE);
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
    }

    private void loggedIn() {
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MEMBER, List.of(), true));
    }

    /**
     * MockMvc 会把 {@code Set-Cookie} 解析后重新拼写（属性名大小写、日期格式），所以这里只比较 名=值；逐字节的头在
     * SiteUserRealHttpTest（真实 Tomcat）与打包 jar 的实测里核对。
     */
    private static List<String> pairs(List<String> headers) {
        return headers.stream().map(h -> h.split(";", 2)[0]).toList();
    }

    // ------------------------------------------------------------------ 登录

    @Test
    void loginPostReadsRawBytesAndReturnsLegacyJsonWithCookies() throws Exception {
        List<String> cookies = MemberCookies.login(7, "member07", 3, "VIP会员", "r", "/static/images/touxiang.png",
                2_000_000_000L);
        when(accounts.login(any(), any(), any())).thenReturn(new AccountReply(1, "登录成功", cookies));
        // 正文里有 PHP 解码后才得到的字节：%00、%FF（非法 UTF-8）、+（空格）
        byte[] body = "user_name=%20al+ice&user_pwd=%00p%FFw+&openid=x&verify=1234".getBytes(StandardCharsets.US_ASCII);
        MvcResult r = mvc.perform(post(LOGIN + "?user_name=ignored&extra=1")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body)
                .header("X-Requested-With", "XMLHttpRequest").header("X-Forwarded-For", "9.9.9.9")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"code\":1,\"msg\":\"登录成功\"}");
        assertThat(pairs(r.getResponse().getHeaders("Set-Cookie"))).isEqualTo(pairs(cookies));
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, PhpInput.Value>> params = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
        verify(accounts).login(params.capture(), any(), ip.capture());
        assertThat(params.getValue().get("user_name").bytes()).as("POST 覆盖 GET").isEqualTo(" al ice".getBytes());
        assertThat(params.getValue().get("user_pwd").bytes()).isEqualTo(new byte[] {0, 'p', (byte) 0xFF, 'w', ' '});
        assertThat(params.getValue().get("extra").text()).isEqualTo("1");
        assertThat(ip.getValue()).as("未配置可信代理时不采用转发头（D2 ④）").isEqualTo("127.0.0.1");
    }

    @Test
    void loginPageHasNoncedScriptHooksAndRedirectsWhenCookiesPresent() throws Exception {
        MvcResult r = mvc.perform(get(LOGIN)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        Matcher m = NONCE.matcher(csp);
        assertThat(m.find()).as(csp).isTrue();
        String html = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("<script nonce=\"" + m.group(1) + "\" src=\"/site/member.js\"></script>",
                "data-member-form=\"login\"", "data-success-url=\"/index.php/user/index.html\"",
                "action=\"/index.php/user/login.html\"", "data-captcha-refresh",
                "src=\"/index.php/verify/index.html\"", "data-member-message", "name=\"verify\"",
                "<meta name=\"robots\" content=\"noindex\">");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(mvc.perform(get(LOGIN)).andReturn().getResponse().getHeader("Content-Security-Policy"))
                .as("每个响应一个 nonce").isNotEqualTo(csp);
        // 旧 User.php:187-189：只看 cookie 是否为真值，不校验
        MvcResult redirect = mvc.perform(get(LOGIN).header("Cookie", "user_id=5; user_name=x")).andReturn();
        assertThat(redirect.getResponse().getStatus()).isEqualTo(302);
        assertThat(redirect.getResponse().getHeader("Location")).isEqualTo("/index.php/user/index.html");
        assertThat(mvc.perform(get(LOGIN).header("Cookie", "user_id=0; user_name=%E6%B8%B8%E5%AE%A2")).andReturn()
                .getResponse().getStatus()).as("游客回写后的 cookie（user_id=0）显示登录页").isEqualTo(200);
    }

    // ------------------------------------------------------------------ 未登录、未开放、未实现

    @Test
    void protectedActionsAnswerNotLoggedInLikeTheLegacyConstructor() throws Exception {
        MvcResult html = mvc.perform(get("/index.php/user/index.html")).andReturn();
        assertThat(html.getResponse().getStatus()).isEqualTo(200);
        assertThat(pairs(html.getResponse().getHeaders("Set-Cookie"))).isEqualTo(pairs(MemberCookies.logout()));
        assertThat(html.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("未登录", "href=\"/index.php/user/login.html\"", "data-site-page=\"user-jump\"");
        MvcResult ajax = mvc.perform(get("/index.php/user/info.html?_ajax=1")).andReturn();
        assertThat(ajax.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":0,\"msg\":\"未登录\",\"data\":\"\",\"url\":\"\\/index.php\\/user\\/login.html\",\"wait\":3}");
        // 旧白名单里没有 regcheck：游客调用同样是未登录
        assertThat(mvc.perform(get("/index.php/user/regcheck.html?t=user_name&str=x")
                .header("X-Requested-With", "XMLHttpRequest")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("\"msg\":\"未登录\"");
        // B11 等后续批次的操作：未登录先给未登录，已登录为 404
        assertThat(mvc.perform(get("/index.php/user/comment.html")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("未登录");
        // B10 的操作在本测试中没有会员财务服务：与 B09 入口在可写数据源关闭时相同，按 503（SiteFinanceMvcTest 覆盖有服务时）
        assertThat(mvc.perform(get("/index.php/user/buy.html")).andReturn().getResponse().getStatus()).isEqualTo(503);
        loggedIn();
        assertThat(mvc.perform(get("/index.php/user/comment.html")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/index.php/user/oauth.html")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void withoutTheWriteDatabaseEveryUserActionIs503() throws Exception {
        when(accounts.available()).thenReturn(false);
        assertThat(mvc.perform(get(LOGIN)).andReturn().getResponse().getStatus()).isEqualTo(503);
        MvcResult ajax = mvc.perform(post(LOGIN).header("X-Requested-With", "XMLHttpRequest")).andReturn();
        assertThat(ajax.getResponse().getStatus()).isEqualTo(503);
        assertThat(ajax.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":503,\"msg\":\"会员功能暂未开放\"}");
        verify(accounts, never()).login(any(), any(), any());
    }

    // ------------------------------------------------------------------ 注销、注册、regcheck

    @Test
    void logoutRedirectsOrAnswersJson() throws Exception {
        when(accounts.logout(any())).thenReturn(new AccountReply(1, "退出成功", MemberCookies.logout()));
        MvcResult r = mvc.perform(get("/index.php/user/logout.html")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getHeader("Location")).isEqualTo(LOGIN);
        assertThat(pairs(r.getResponse().getHeaders("Set-Cookie"))).isEqualTo(pairs(MemberCookies.logout()));
        MvcResult ajax = mvc.perform(post("/index.php/user/logout").header("X-Requested-With", "xmlhttprequest"))
                .andReturn();
        assertThat(ajax.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":1,\"msg\":\"退出成功\"}");
    }

    @Test
    void regPageStoresTheInviterAndRegcheckAnswersTextOrJson() throws Exception {
        MvcResult page = mvc.perform(get("/index.php/user/reg/uid/42.html")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(pairs(page.getResponse().getHeaders("Set-Cookie"))).contains("uid=42");
        assertThat(page.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("data-member-form=\"reg\"", "name=\"user_pwd2\"").doesNotContain("name=\"verify\"");
        loggedIn();
        when(accounts.regcheck(any(), any())).thenReturn("x&lt;y");
        MvcResult taken = mvc.perform(get("/index.php/user/regcheck?t=user_name&str=x%3Cy")).andReturn();
        assertThat(taken.getResponse().getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(taken.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("x&lt;y");
        when(accounts.regcheck(any(), any())).thenReturn(null);
        assertThat(mvc.perform(get("/index.php/user/regcheck?t=user_name&str=free")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"code\":1,\"msg\":\"ok\"}");
    }

    // ------------------------------------------------------------------ 资料、头像

    @Test
    void infoUsesThinkPhpSuccessAndErrorShapes() throws Exception {
        loggedIn();
        when(accounts.updateProfile(any(), any())).thenReturn(new AccountReply(1, "保存成功!", List.of()));
        MvcResult ok = mvc.perform(post("/index.php/user/info.html").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("user_pwd=a").header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "http://maccms.local.test/index.php/user/info.html")).andReturn();
        assertThat(ok.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(
                "{\"code\":1,\"msg\":\"保存成功!\",\"data\":\"\",\"url\":\"http:\\/\\/maccms.local.test\\/index.php\\/user\\/info.html\",\"wait\":3}");
        MvcResult noReferer = mvc.perform(post("/index.php/user/info.html").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("user_pwd=a").header("X-Requested-With", "XMLHttpRequest")).andReturn();
        assertThat(noReferer.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"url\":null");
        when(accounts.updateProfile(any(), any())).thenReturn(AccountReply.of(0, "原密码错误"));
        MvcResult err = mvc.perform(post("/index.php/user/info.html").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("user_pwd=a").header("X-Requested-With", "XMLHttpRequest")).andReturn();
        assertThat(err.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":0,\"msg\":\"原密码错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
        MvcResult html = mvc.perform(post("/index.php/user/info.html").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("user_pwd=a")).andReturn();
        assertThat(html.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("原密码错误", "role=\"alert\"");
        MvcResult page = mvc.perform(get("/index.php/user/info.html")).andReturn();
        assertThat(page.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("value=\"昵称&amp;\"", "value=\"问&amp;题\"", "data-member-form=\"info\"");
    }

    @Test
    void portraitAnswersLegacyKeyOrder() throws Exception {
        loggedIn();
        when(accounts.portrait(any(), any(), any(), any()))
                .thenReturn(new PortraitReply(1, "文件上传成功！", "/upload/user/7/7.jpg?1234", false));
        MockMultipartFile file = new MockMultipartFile("file", "me.png", "image/png", new byte[] {1, 2, 3});
        MvcResult r = mvc.perform(multipart("/index.php/user/portrait.html").file(file)).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"msg\":\"文件上传成功！\",\"code\":1,\"file\":\"\\/upload\\/user\\/7\\/7.jpg?1234\"}");
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(accounts).portrait(any(), bytes.capture(), any(), any());
        assertThat(bytes.getValue()).containsExactly(1, 2, 3);
        when(accounts.portrait(any(), any(), any(), any()))
                .thenReturn(new PortraitReply(0, "未开启自定义头像功能", null, true));
        assertThat(mvc.perform(multipart("/index.php/user/portrait.html").file(file)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"code\":0,\"msg\":\"未开启自定义头像功能\"}");
    }

    // ------------------------------------------------------------------ label_user 接线

    @Test
    void labelUserWritesGuestResetAndScopesTheViewerGroupForTheRequestOnly() throws Exception {
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), MemberCookies.guestReset(),
                true));
        MvcResult reset = mvc.perform(get("/_site/placeholder").header("Cookie", "user_id=1; user_name=a; user_check=b"))
                .andReturn();
        assertThat(pairs(reset.getResponse().getHeaders("Set-Cookie"))).isEqualTo(pairs(MemberCookies.guestReset()));
        assertThat(reset.getResponse().getHeader("Cache-Control")).as("写了 cookie 的响应不缓存").isEqualTo("no-store");

        loggedIn();
        MvcResult member = mvc.perform(get("/_site/placeholder").header("Cookie", "user_id=7")).andReturn();
        assertThat(member.getResponse().getHeader("Cache-Control")).as("会员组页面不进共享缓存").isEqualTo("private, no-cache");
        assertThat(member.getResponse().getHeaders("Set-Cookie")).isEmpty();

        AtomicInteger seen = new AtomicInteger();
        when(accounts.pageSettings()).thenAnswer(inv -> {
            seen.set(ContentViewer.groupId());
            return new MemberPageSettings(true, false, false, true, false, false, true);
        });
        mvc.perform(get("/index.php/user/ajax_login.html")).andReturn();
        assertThat(seen.get()).as("处理请求时按有效会员组").isEqualTo(3);
        assertThat(ContentViewer.groupId()).as("请求结束后回到游客").isEqualTo(1);

        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
        MvcResult guest = mvc.perform(get("/_site/placeholder")).andReturn();
        assertThat(guest.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void verifyIsNotPartOfLabelUser() throws Exception {
        mvc.perform(get("/index.php/verify/check.html?verify=1").header("Cookie", "user_id=1; user_name=a; user_check=b"))
                .andReturn();
        verify(sessions, never()).resolvePage(any());
    }

    @Test
    void sendMessageAndFindpassResetAnswerJson() throws Exception {
        when(accounts.sendMessage(any(), any(), anyInt())).thenReturn(AccountReply.of(9009, "验证码发送失败：未配置短信发送服务"));
        assertThat(mvc.perform(post("/index.php/user/reg_msg").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("ac=phone&to=1")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":9009,\"msg\":\"验证码发送失败：未配置短信发送服务\"}");
        verify(accounts).sendMessage(any(), any(), org.mockito.ArgumentMatchers.eq(3));
        mvc.perform(post("/index.php/user/findpass_msg").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("ac=phone&to=1")).andReturn();
        verify(accounts).sendMessage(any(), any(), org.mockito.ArgumentMatchers.eq(2));
        MvcResult page = mvc.perform(get("/index.php/user/findpass_msg/ac/phone.html")).andReturn();
        assertThat(page.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("通过手机找回密码", "data-member-send", "data-send-url=\"/index.php/user/findpass_msg.html\"",
                        "value=\"phone\"");
        MvcResult findpass = mvc.perform(get("/index.php/user/findpass.html")).andReturn();
        assertThat(findpass.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("href=\"/index.php/user/findpass_msg/ac/email.html\"", "name=\"verify\"");
        assertThat(mvc.perform(get("/index.php/user/findpass_reset")).andReturn().getResponse().getContentAsString())
                .as("旧版 GET 时没有返回值").isEmpty();
        when(accounts.findpassReset(any(), any())).thenReturn(AccountReply.of(9002, "验证信息错误，请重试"));
        assertThat(mvc.perform(post("/index.php/user/findpass_reset").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("ac=phone&to=1&code=1&user_pwd=123456&user_pwd2=123456")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"code\":9002,\"msg\":\"验证信息错误，请重试\"}");
    }

    /** B25：绑定、解绑与绑定验证码都要登录（旧白名单里没有它们）；登录后按旧控制器分派，传入本次的登录态。 */
    @Test
    void bindUnbindAndBindmsgRequireLoginAndPassTheMember() throws Exception {
        for (String action : List.of("bind", "unbind", "bindmsg")) {
            assertThat(mvc.perform(post("/index.php/user/" + action).header("X-Requested-With", "XMLHttpRequest"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).as(action).contains("\"msg\":\"未登录\"");
        }
        verify(accounts, never()).bind(any(), any());
        loggedIn();
        when(accounts.sendMessage(any(), any(), anyInt())).thenReturn(AccountReply.of(1, "验证码发送成功，请前往查看"));
        when(accounts.bind(any(), any())).thenReturn(AccountReply.of(1, "绑定成功"));
        when(accounts.unbind(any(), any())).thenReturn(AccountReply.of(1, "解除绑定成功"));
        assertThat(mvc.perform(get("/index.php/user/bindmsg.html?ac=phone&to=13800000001")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).as("旧版 bindmsg 不看请求方法")
                .isEqualTo("{\"code\":1,\"msg\":\"验证码发送成功，请前往查看\"}");
        verify(accounts).sendMessage(org.mockito.ArgumentMatchers.eq(MEMBER), any(), org.mockito.ArgumentMatchers.eq(1));
        assertThat(mvc.perform(post("/index.php/user/bind").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("ac=phone&to=13800000001&code=123456")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"code\":1,\"msg\":\"绑定成功\"}");
        verify(accounts).bind(org.mockito.ArgumentMatchers.eq(MEMBER), any());
        assertThat(mvc.perform(post("/index.php/user/unbind").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("ac=email")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":1,\"msg\":\"解除绑定成功\"}");
        verify(accounts).unbind(org.mockito.ArgumentMatchers.eq(MEMBER), any());
        String bindPage = mvc.perform(get("/index.php/user/bind.html")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(bindPage).as("ac 缺省为 email").contains("绑定邮箱", "data-member-form=\"bind\"", "value=\"email\"",
                "data-send-url=\"/index.php/user/bindmsg.html\"", "href=\"/index.php/user/bind/ac/phone.html\"");
        assertThat(mvc.perform(get("/index.php/user/bind/ac/phone.html")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("绑定手机", "value=\"phone\"");
        assertThat(mvc.perform(get("/index.php/user/unbind/ac/phone.html")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("解除绑定手机", "data-member-form=\"unbind\"",
                "name=\"ac\" value=\"phone\"");
    }
}

package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.integration.application.OAuthLogin;
import com.maccms.backend.integration.application.OAuthOutcome;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberCookies;
import com.maccms.backend.member.application.MemberOAuthService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.site.config.SiteConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B26 第三方登录的 HTTP 流程（34 文档第 2、5 节）：真实的 {@link MemberOAuthService} 与站点会话，第三方协议与会员写库是替身
 * （协议见 OAuthLoginTest，写库见 OAuthLoginMySqlIT）。覆盖 {@code state} 的一次性、会话绑定、时效、方式与登录身份，重放、伪造、
 * 无会话都在出站之前拒绝；回调中的 openid、col、跳转地址参数一律不用；登录页按开关显示入口。
 */
@WebMvcTest(controllers = {SiteUserController.class, SitePlaceholderController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=https://maccms.local.test/",
                "maccms.legacy-config.dir=../application"})
@Import({SiteConfiguration.class, ClientIpConfiguration.class, SiteOAuthMvcTest.Wiring.class})
class SiteOAuthMvcTest {

    static final String OPENID = "0123456789ABCDEF0123456789ABCDEF";
    static final String CALLBACK = "https://maccms.local.test/index.php/user/logincallback/type/";
    private static final MemberGroup VIP = new MemberGroup(3, "VIP会员", 1, ",1,", "{}");
    private static final MemberState MEMBER = new MemberState(true, 7, "member07", 3, 3, VIP, 0, false);

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeOAuth fake;
    @Autowired
    MutableClock clock;
    @MockitoBean
    MemberAccountService accounts;
    @MockitoBean
    MemberSessionService sessions;

    /** 第三方协议替身：记录每次换取，按设定返回。 */
    static final class FakeOAuth implements OAuthLogin {
        final Set<String> enabled = new HashSet<>();
        final List<String> fetchedCodes = new ArrayList<>();
        final List<String> callbacks = new ArrayList<>();
        OAuthOutcome outcome = OAuthOutcome.ok(OPENID, "昵称");

        @Override
        public boolean enabled(String type) {
            return enabled.contains(type);
        }

        @Override
        public String authorizeUrl(String type, String state, String callback) {
            return "https://auth." + type + ".example.test/authorize?state=" + state + "&redirect_uri=" + callback;
        }

        @Override
        public OAuthOutcome fetch(String type, String code, String callback) {
            fetchedCodes.add(code);
            callbacks.add(callback);
            return outcome;
        }
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-29T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        FakeOAuth fakeOAuth() {
            return new FakeOAuth();
        }

        @Bean
        MutableClock mutableClock() {
            return new MutableClock();
        }

        @Bean
        MemberOAuthService memberOAuthService(FakeOAuth fake, MemberAccountService accounts, MutableClock clock) {
            return new MemberOAuthService(fake, accounts, "https://maccms.local.test/", clock, new SecureRandom());
        }
    }

    @BeforeEach
    void stubs() {
        fake.enabled.clear();
        fake.enabled.addAll(List.of("qq", "weixin"));
        fake.fetchedCodes.clear();
        fake.callbacks.clear();
        fake.outcome = OAuthOutcome.ok(OPENID, "昵称");
        clock.now = Instant.parse("2026-10-29T08:00:00Z");
        when(accounts.available()).thenReturn(true);
        when(accounts.pageSettings()).thenReturn(new MemberPageSettings(true, false, false, true, false, false, true));
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
        when(accounts.openidLogin(anyBoolean(), anyString(), any())).thenReturn(new AccountReply(1, "登录成功",
                MemberCookies.login(9, "01234567", 2, "普通会员", "r", "/static/images/touxiang.png", 1_800_000_000L)));
        when(accounts.openidBind(any(), anyBoolean(), anyString())).thenReturn(AccountReply.of(1, "绑定成功"));
    }

    private void loggedIn() {
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MEMBER, List.of(), true));
    }

    /** 发起授权：返回会话 cookie 与 state。 */
    private Started start(String type, Cookie session) throws Exception {
        var req = get("/index.php/user/oauth/type/" + type + ".html");
        if (session != null) {
            req.cookie(session);
        }
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        String location = r.getResponse().getHeader("Location");
        assertThat(location).startsWith("https://auth." + type + ".example.test/authorize?state=")
                .endsWith("&redirect_uri=" + CALLBACK + type);
        String state = location.substring(location.indexOf("state=") + 6, location.indexOf('&'));
        Cookie c = r.getResponse().getCookie(SiteSessions.COOKIE);
        return new Started(c != null ? c : session, state);
    }

    private record Started(Cookie session, String state) {
    }

    private MvcResult callback(String type, Cookie session, String query) throws Exception {
        var req = get("/index.php/user/logincallback/type/" + type + "?" + query);
        if (session != null) {
            req.cookie(session);
        }
        return mvc.perform(req).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void loginPageShowsOnlyEnabledProviders() throws Exception {
        String page = body(mvc.perform(get("/index.php/user/login.html")).andReturn());
        assertThat(page).contains("href=\"/index.php/user/oauth/type/qq.html\"", ">QQ登录<",
                "href=\"/index.php/user/oauth/type/weixin.html\"", ">微信登录<");
        fake.enabled.remove("weixin");
        page = body(mvc.perform(get("/index.php/user/login.html")).andReturn());
        assertThat(page).contains("QQ登录").doesNotContain("微信登录").doesNotContain("user/oauth/type/weixin");
        fake.enabled.clear();
        page = body(mvc.perform(get("/index.php/user/login.html")).andReturn());
        assertThat(page).doesNotContain("user/oauth").doesNotContain("QQ登录");
        assertThat(body(mvc.perform(get("/index.php/user/ajax_login.html")).andReturn())).doesNotContain("user/oauth");
    }

    @Test
    void startIssuesASessionAndAFreshStateEachTime() throws Exception {
        Started a = start("qq", null);
        assertThat(a.session()).as("没有会话时新建并写出会话 cookie").isNotNull();
        assertThat(a.session().isHttpOnly()).isTrue();
        assertThat(a.state()).matches("[0-9a-f]{64}");
        Started b = start("qq", a.session());
        assertThat(b.session().getValue()).isEqualTo(a.session().getValue());
        assertThat(b.state()).isNotEqualTo(a.state());
        // 未打开、不支持、缺 type：参数错误，不跳转
        fake.enabled.remove("weixin");
        for (String path : List.of("/index.php/user/oauth/type/weixin.html", "/index.php/user/oauth/type/weibo.html",
                "/index.php/user/oauth.html")) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(r.getResponse().getHeader("Location")).isNull();
            assertThat(body(r)).contains("参数错误");
        }
        // 授权页地址的回调是部署配置中的固定值：请求的 Host 与跳转参数不影响它
        MvcResult r = mvc.perform(get("/index.php/user/oauth/type/qq.html?redirect_uri=https://evil.test/&callback=x")
                .header("Host", "evil.test")).andReturn();
        assertThat(r.getResponse().getHeader("Location")).endsWith("&redirect_uri=" + CALLBACK + "qq")
                .doesNotContain("evil");
    }

    @Test
    void normalGuestFlowLogsInWithTheServerSideOpenidAndIgnoresRequestOpenid() throws Exception {
        Started s = start("qq", null);
        MvcResult r = callback("qq", s.session(), "code=AUTHCODE&state=" + s.state()
                + "&openid=ATTACKER&col=user_openid_weixin&redirect=https://evil.test/&url=https://evil.test/");
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/index.php/user/index.html");
        assertThat(r.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("user_check="));
        assertThat(fake.fetchedCodes).containsExactly("AUTHCODE");
        assertThat(fake.callbacks).containsExactly(CALLBACK + "qq");
        verify(accounts).openidLogin(eq(true), eq(OPENID), any());
        verify(accounts, never()).openidBind(any(), anyBoolean(), anyString());
    }

    @Test
    void replayedForgedMissingOrForeignStatesAreRejectedBeforeAnyOutboundCall() throws Exception {
        Started s = start("qq", null);
        assertThat(callback("qq", s.session(), "code=C1&state=" + s.state()).getResponse().getStatus()).isEqualTo(302);
        // 重放：同一个 state 第二次用
        MvcResult replay = callback("qq", s.session(), "code=C2&state=" + s.state());
        assertThat(body(replay)).contains("登录请求已失效，请重新登录");
        // 伪造：state 不对，且这次失败也作废了待用的 state
        Started f = start("qq", s.session());
        assertThat(body(callback("qq", f.session(), "code=C3&state=" + "0".repeat(64)))).contains("登录请求已失效");
        assertThat(body(callback("qq", f.session(), "code=C4&state=" + f.state()))).contains("登录请求已失效");
        // 缺 state、没有会话、别的会话发起的 state（登录 CSRF：把攻击者的回调地址发给受害者）
        Started g = start("qq", null);
        assertThat(body(callback("qq", g.session(), "code=C5"))).contains("登录请求已失效");
        Started h = start("qq", null);
        assertThat(body(callback("qq", null, "code=C6&state=" + h.state()))).contains("登录请求已失效");
        Started victim = start("qq", null);
        assertThat(body(callback("qq", victim.session(), "code=C7&state=" + h.state()))).contains("登录请求已失效");
        // 方式不符：为 qq 发起，回调到 weixin
        Started w = start("qq", null);
        assertThat(body(callback("weixin", w.session(), "code=C8&state=" + w.state()))).contains("登录请求已失效");
        // 超时
        Started old = start("qq", null);
        clock.now = clock.now.plus(MemberOAuthService.STATE_TTL).plus(Duration.ofSeconds(1));
        assertThat(body(callback("qq", old.session(), "code=C9&state=" + old.state()))).contains("登录请求已失效");
        assertThat(fake.fetchedCodes).as("只有第一次合法回调发起了换取").containsExactly("C1");
        verify(accounts).openidLogin(anyBoolean(), anyString(), any());
    }

    @Test
    void missingCodeOrTypeIsAParameterErrorAndConsumesTheState() throws Exception {
        Started s = start("qq", null);
        assertThat(body(callback("qq", s.session(), "state=" + s.state() + "&error=access_denied"))).contains("参数错误");
        assertThat(body(callback("qq", s.session(), "code=C&state=" + s.state()))).contains("登录请求已失效");
        assertThat(body(mvc.perform(get("/index.php/user/logincallback?code=C")).andReturn())).contains("参数错误");
        // 未打开的方式
        Started w = start("weixin", null);
        fake.enabled.remove("weixin");
        assertThat(body(callback("weixin", w.session(), "code=C&state=" + w.state()))).contains("参数错误");
        assertThat(fake.fetchedCodes).isEmpty();
        // ajax：ThinkPHP error() 的 JSON
        MvcResult ajax = mvc.perform(get("/index.php/user/logincallback/type/qq?code=C&state=x")
                .header("X-Requested-With", "XMLHttpRequest")).andReturn();
        assertThat(body(ajax)).isEqualTo("{\"code\":0,\"msg\":\"登录请求已失效，请重新登录\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
    }

    @Test
    void providerFailuresUseTheLegacyMessages() throws Exception {
        fake.outcome = OAuthOutcome.tokenFailed();
        Started a = start("qq", null);
        assertThat(body(callback("qq", a.session(), "code=C&state=" + a.state()))).contains("获取第三方用户信息失败，请重试");
        fake.outcome = OAuthOutcome.userInfoFailed("<b>bad</b>");
        Started b = start("qq", null);
        assertThat(body(callback("qq", b.session(), "code=C&state=" + b.state())))
                .contains("获取腾讯QQ用户信息失败：&lt;b&gt;bad&lt;/b&gt;");
        Started c = start("weixin", null);
        assertThat(body(callback("weixin", c.session(), "code=C&state=" + c.state())))
                .contains("获取微信用户信息失败：");
        fake.outcome = OAuthOutcome.ok(OPENID, "n");
        when(accounts.openidLogin(anyBoolean(), anyString(), any()))
                .thenReturn(AccountReply.of(1010, "同步信息注册失败，请联系管理员"));
        Started d = start("qq", null);
        MvcResult r = callback("qq", d.session(), "code=C&state=" + d.state());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("同步信息注册失败，请联系管理员");
        assertThat(r.getResponse().getHeaders("Set-Cookie")).noneMatch(h -> h.startsWith("user_check="));
    }

    @Test
    void loggedInMemberBindsAndGetsLegacyJson() throws Exception {
        loggedIn();
        Started s = start("weixin", null);
        MvcResult r = callback("weixin", s.session(), "code=C&state=" + s.state() + "&openid=ATTACKER");
        assertThat(body(r)).isEqualTo("{\"code\":1,\"msg\":\"绑定成功\"}");
        verify(accounts).openidBind(eq(MEMBER), eq(false), eq(OPENID));
        verify(accounts, never()).openidLogin(anyBoolean(), anyString(), any());
    }

    @Test
    void loginIdentityMustNotChangeBetweenStartAndCallback() throws Exception {
        // 游客发起、回调时已是会员（或反过来）：拒绝，避免把别人发起的授权绑定到当前账号
        Started guest = start("qq", null);
        loggedIn();
        assertThat(body(callback("qq", guest.session(), "code=C&state=" + guest.state()))).contains("登录请求已失效");
        Started member = start("qq", null);
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
        assertThat(body(callback("qq", member.session(), "code=C&state=" + member.state()))).contains("登录请求已失效");
        assertThat(fake.fetchedCodes).isEmpty();
        verify(accounts, never()).openidBind(any(), anyBoolean(), anyString());
    }

    @Test
    void ordinaryLoginStillIgnoresOpenidAndCol() throws Exception {
        // 普通登录入口的参数照旧交给 MemberAccountService.login（它忽略 openid/col，见 MemberAccountGoldenMySqlIT 与 OAuthLoginMySqlIT）
        when(accounts.login(any(), any(), any())).thenReturn(AccountReply.of(1001, "请填写必填项"));
        assertThat(body(mvc.perform(post("/index.php/user/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("openid=" + OPENID + "&col=user_openid_qq")).andReturn()))
                .isEqualTo("{\"code\":1001,\"msg\":\"请填写必填项\"}");
        verify(accounts, never()).openidLogin(anyBoolean(), anyString(), any());
    }
}

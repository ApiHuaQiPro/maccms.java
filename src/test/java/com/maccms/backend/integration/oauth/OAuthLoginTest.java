package com.maccms.backend.integration.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.application.OAuthLogin;
import com.maccms.backend.integration.application.OAuthOutcome;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 第三方登录的协议实现（E10，B26；34 文档第 3 节）：授权页地址、QQ 与微信换令牌/openid/用户信息的请求逐项、各种失败按结果返回而不抛出，
 * 令牌被换成别的应用的、用户信息里的 openid 与令牌不符时拒绝；授权码、令牌、密钥、openid 不进日志。只访问本机模拟服务。
 */
@ExtendWith(OutputCaptureExtension.class)
class OAuthLoginTest {

    private static final String QQ_ID = "101000001";
    private static final String QQ_SECRET = "test-only-qq-secret";
    private static final String WX_ID = "wx0000000000000001";
    private static final String WX_SECRET = "test-only-weixin-secret";
    private static final String CALLBACK = "https://www.example.test/index.php/user/logincallback/type/qq";
    private static final String QQ_OPENID = "0123456789ABCDEF0123456789ABCDEF";
    private static final String WX_OPENID = "oTest_openid-0000000000000001";
    private static final String TOKEN = "test-only-access-token";
    private static final String CODE = "test-only-code";

    private LoopbackMockServer mock;

    @BeforeEach
    void setUp() {
        mock = LoopbackMockServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    static ThirdPartyConfig config(String qqStatus, String wxStatus) {
        Map<Object, PhpValue> qq = new LinkedHashMap<>();
        qq.put("status", new PhpValue.PhpString(qqStatus));
        qq.put("key", new PhpValue.PhpString(QQ_ID));
        qq.put("secret", new PhpValue.PhpString(QQ_SECRET));
        Map<Object, PhpValue> wx = new LinkedHashMap<>();
        wx.put("status", new PhpValue.PhpString(wxStatus));
        wx.put("key", new PhpValue.PhpString(WX_ID));
        wx.put("secret", new PhpValue.PhpString(WX_SECRET));
        Map<Object, PhpValue> connect = new LinkedHashMap<>();
        connect.put("qq", new PhpArray(qq));
        connect.put("weixin", new PhpArray(wx));
        return ThirdPartyConfig.from(new PhpArray(Map.of("connect", new PhpArray(connect))));
    }

    private OAuthLogin login(ThirdPartyConfig config) {
        return login(config, mock.uri("/qq/").toString(), mock.uri("/wxapi/").toString());
    }

    private static OAuthLogin login(ThirdPartyConfig config, String qqBase, String wxApi) {
        OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                IntegrationPackages.REGISTERED.keySet(), Duration.ofSeconds(2), Duration.ofSeconds(3), 1 << 16,
                config.secretValues());
        return OAuthConfiguration.create(client, config, qqBase, "https://open.weixin.example.test/", wxApi);
    }

    private void stubQq(MockResponse token, MockResponse me, MockResponse info) {
        mock.stub("POST", "/qq/oauth2.0/token", token);
        mock.stub("GET", "/qq/oauth2.0/me", me);
        mock.stub("GET", "/qq/user/get_user_info", info);
    }

    private static MockResponse qqMe(String clientId, String openid) {
        return MockResponse.text(200, "callback( {\"client_id\":\"" + clientId + "\",\"openid\":\"" + openid + "\"} );\n");
    }

    private static Map<String, String> query(String q) {
        return Form.parse(q == null ? "" : q);
    }

    @Test
    void enabledNeedsStatusOneAndBothCredentials() {
        assertThat(login(config("1", "1")).enabled("qq")).isTrue();
        assertThat(login(config("0", "1")).enabled("qq")).isFalse();
        assertThat(login(config("1", "0")).enabled("weixin")).isFalse();
        assertThat(login(config("1", "1")).enabled("weibo")).isFalse();
        assertThat(login(ThirdPartyConfig.from(null)).enabled("qq")).isFalse();
        Map<Object, PhpValue> qq = new LinkedHashMap<>();
        qq.put("status", new PhpValue.PhpString("1"));
        qq.put("key", new PhpValue.PhpString(QQ_ID));
        ThirdPartyConfig noSecret = ThirdPartyConfig.from(new PhpArray(Map.of("connect",
                new PhpArray(Map.of("qq", new PhpArray(qq))))));
        assertThat(login(noSecret).enabled("qq")).isFalse();
        assertThatThrownBy(() -> login(config("0", "0")).authorizeUrl("qq", "s", CALLBACK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void authorizeUrlsCarryAppIdCallbackAndState() {
        OAuthLogin o = login(config("1", "1"));
        String qq = o.authorizeUrl("qq", "abc123", CALLBACK);
        URI u = URI.create(qq);
        assertThat(u.getPath()).isEqualTo("/qq/oauth2.0/authorize");
        assertThat(query(u.getRawQuery())).containsExactly(Map.entry("response_type", "code"),
                Map.entry("client_id", QQ_ID), Map.entry("redirect_uri", CALLBACK), Map.entry("state", "abc123"),
                Map.entry("scope", "get_user_info"));
        String wx = o.authorizeUrl("weixin", "abc123", CALLBACK);
        assertThat(wx).startsWith("https://open.weixin.example.test/connect/qrconnect?").endsWith("#wechat_redirect");
        assertThat(query(URI.create(wx).getRawQuery())).containsExactly(Map.entry("appid", WX_ID),
                Map.entry("redirect_uri", CALLBACK), Map.entry("response_type", "code"),
                Map.entry("scope", "snsapi_login"), Map.entry("state", "abc123"));
        // 密钥不进授权页地址
        assertThat(qq + wx).doesNotContain(QQ_SECRET).doesNotContain(WX_SECRET);
    }

    @Test
    void qqNormalFlowWithJsonToken(CapturedOutput output) {
        stubQq(MockResponse.json(200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":\"7776000\"}"),
                qqMe(QQ_ID, QQ_OPENID),
                MockResponse.json(200, "{\"ret\":0,\"msg\":\"\",\"nickname\":\"测试昵称\",\"figureurl_2\":\"x\"}"));
        OAuthOutcome out = login(config("1", "1")).fetch("qq", CODE, CALLBACK);
        assertThat(out.status()).isEqualTo(OAuthOutcome.Status.OK);
        assertThat(out.openid()).isEqualTo(QQ_OPENID);
        assertThat(out.nickname()).isEqualTo("测试昵称");
        var reqs = mock.requests();
        assertThat(reqs).hasSize(3);
        assertThat(reqs.get(0).header("content-type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(query(reqs.get(0).bodyText())).containsExactly(Map.entry("grant_type", "authorization_code"),
                Map.entry("client_id", QQ_ID), Map.entry("client_secret", QQ_SECRET), Map.entry("code", CODE),
                Map.entry("redirect_uri", CALLBACK), Map.entry("fmt", "json"));
        assertThat(reqs.get(0).query()).as("密钥只在请求体里").isNullOrEmpty();
        assertThat(query(reqs.get(1).query())).containsExactly(Map.entry("access_token", TOKEN), Map.entry("fmt", "json"));
        assertThat(query(reqs.get(2).query())).containsExactly(Map.entry("access_token", TOKEN),
                Map.entry("oauth_consumer_key", QQ_ID), Map.entry("openid", QQ_OPENID), Map.entry("format", "json"));
        assertThat(output.getAll()).contains("oauth fetch type=qq result=OK").doesNotContain(TOKEN)
                .doesNotContain(CODE).doesNotContain(QQ_SECRET).doesNotContain(QQ_OPENID);
    }

    @Test
    void qqQueryStringTokenAndPlainJsonMe() {
        stubQq(MockResponse.text(200, "access_token=" + TOKEN + "&expires_in=7776000&refresh_token=r"),
                MockResponse.json(200, "{\"client_id\":\"" + QQ_ID + "\",\"openid\":\"" + QQ_OPENID + "\"}"),
                MockResponse.json(200, "{\"ret\":\"0\",\"nickname\":\"n\"}"));
        assertThat(login(config("1", "1")).fetch("qq", CODE, CALLBACK).openid()).isEqualTo(QQ_OPENID);
    }

    @Test
    void qqTokenFailures() {
        OAuthLogin o = login(config("1", "1"));
        // 错误响应（JSONP）、缺 expires_in 的查询串、HTTP 500、非 JSON
        for (MockResponse token : new MockResponse[] {
                MockResponse.text(200, "callback( {\"error\":100019,\"error_description\":\"code to access token error\"} );"),
                MockResponse.text(200, "access_token=" + TOKEN),
                MockResponse.json(500, "{\"access_token\":\"" + TOKEN + "\"}"),
                MockResponse.text(200, "<html>")}) {
            stubQq(token, qqMe(QQ_ID, QQ_OPENID), MockResponse.json(200, "{\"ret\":0}"));
            assertThat(o.fetch("qq", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        }
        String okToken = "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":1}";
        // 令牌属于别的应用：不接受其 openid
        stubQq(MockResponse.json(200, okToken), qqMe("999999", QQ_OPENID), MockResponse.json(200, "{\"ret\":0}"));
        assertThat(o.fetch("qq", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        // openid 形态不对（超过列宽、含别的字符、为空）
        for (String bad : new String[] {"A".repeat(41), "abc'--", ""}) {
            stubQq(MockResponse.json(200, okToken), qqMe(QQ_ID, bad), MockResponse.json(200, "{\"ret\":0}"));
            assertThat(o.fetch("qq", CODE, CALLBACK).status()).as(bad).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        }
        // 未打开时不发请求
        int before = mock.requests().size();
        assertThat(login(config("0", "1")).fetch("qq", CODE, CALLBACK).status())
                .isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        assertThat(mock.requests()).hasSize(before);
    }

    @Test
    void qqUserInfoFailures() {
        OAuthLogin o = login(config("1", "1"));
        String okToken = "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":1}";
        stubQq(MockResponse.json(200, okToken), qqMe(QQ_ID, QQ_OPENID),
                MockResponse.json(200, "{\"ret\":-1,\"msg\":\"client request's parameters are invalid\"}"));
        OAuthOutcome out = o.fetch("qq", CODE, CALLBACK);
        assertThat(out.status()).isEqualTo(OAuthOutcome.Status.USER_INFO_FAILED);
        assertThat(out.message()).isEqualTo("client request's parameters are invalid");
        // 旧版对非 JSON 的用户信息按成功处理（null == 0），新版按失败
        stubQq(MockResponse.json(200, okToken), qqMe(QQ_ID, QQ_OPENID), MockResponse.text(200, "oops"));
        assertThat(o.fetch("qq", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.USER_INFO_FAILED);
        stubQq(MockResponse.json(200, okToken), qqMe(QQ_ID, QQ_OPENID), MockResponse.json(200, "{\"nickname\":\"x\"}"));
        assertThat(o.fetch("qq", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.USER_INFO_FAILED);
    }

    @Test
    void weixinNormalFlow(CapturedOutput output) {
        mock.stub("GET", "/wxapi/sns/oauth2/access_token", MockResponse.json(200, "{\"access_token\":\"" + TOKEN
                + "\",\"expires_in\":7200,\"refresh_token\":\"r\",\"openid\":\"" + WX_OPENID + "\",\"scope\":\"snsapi_login\"}"));
        mock.stub("GET", "/wxapi/sns/userinfo", MockResponse.json(200, "{\"openid\":\"" + WX_OPENID
                + "\",\"nickname\":\"微信昵称\",\"headimgurl\":\"x\"}"));
        OAuthOutcome out = login(config("1", "1")).fetch("weixin", CODE, CALLBACK);
        assertThat(out.status()).isEqualTo(OAuthOutcome.Status.OK);
        assertThat(out.openid()).isEqualTo(WX_OPENID);
        assertThat(out.nickname()).isEqualTo("微信昵称");
        var reqs = mock.requests();
        assertThat(query(reqs.get(0).query())).containsExactly(Map.entry("appid", WX_ID), Map.entry("secret", WX_SECRET),
                Map.entry("code", CODE), Map.entry("grant_type", "authorization_code"));
        assertThat(query(reqs.get(1).query())).containsExactly(Map.entry("access_token", TOKEN),
                Map.entry("openid", WX_OPENID));
        assertThat(output.getAll()).contains("oauth fetch type=weixin result=OK").doesNotContain(TOKEN)
                .doesNotContain(WX_SECRET).doesNotContain(WX_OPENID).doesNotContain(CODE);
    }

    @Test
    void weixinFailures() {
        OAuthLogin o = login(config("1", "1"));
        mock.stub("GET", "/wxapi/sns/oauth2/access_token",
                MockResponse.json(200, "{\"errcode\":40029,\"errmsg\":\"invalid code\"}"));
        assertThat(o.fetch("weixin", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        mock.stub("GET", "/wxapi/sns/oauth2/access_token",
                MockResponse.json(200, "{\"access_token\":\"" + TOKEN + "\",\"openid\":\"" + WX_OPENID + "\"}"));
        mock.stub("GET", "/wxapi/sns/userinfo", MockResponse.json(200, "{\"errcode\":40003,\"errmsg\":\"invalid openid\"}"));
        OAuthOutcome info = o.fetch("weixin", CODE, CALLBACK);
        assertThat(info.status()).isEqualTo(OAuthOutcome.Status.USER_INFO_FAILED);
        assertThat(info.message()).isEqualTo("invalid openid");
        // 用户信息里的 openid 与令牌不符
        mock.stub("GET", "/wxapi/sns/userinfo", MockResponse.json(200, "{\"openid\":\"oOther\",\"nickname\":\"x\"}"));
        assertThat(o.fetch("weixin", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.USER_INFO_FAILED);
    }

    @Test
    void networkFailuresAreReportedNotThrown() throws Exception {
        int dead;
        try (ServerSocket s = new ServerSocket(0)) {
            dead = s.getLocalPort();
        }
        OAuthLogin refused = login(config("1", "1"), "http://127.0.0.1:" + dead + "/qq/",
                "http://127.0.0.1:" + dead + "/wx/");
        assertThat(refused.fetch("qq", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        assertThat(refused.fetch("weixin", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        // loopback-only 下默认的外部地址：不连接，按失败返回
        OAuthLogin external = login(config("1", "1"), OAuthConfiguration.DEFAULT_QQ, OAuthConfiguration.DEFAULT_WEIXIN_API);
        assertThat(external.fetch("qq", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        assertThat(external.fetch("weixin", CODE, CALLBACK).status()).isEqualTo(OAuthOutcome.Status.TOKEN_FAILED);
        // 用户信息超时
        stubQq(MockResponse.json(200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":1}"), qqMe(QQ_ID, QQ_OPENID),
                MockResponse.json(200, "{\"ret\":0}").withDelay(Duration.ofSeconds(5)));
        assertThat(login(config("1", "1")).fetch("qq", CODE, CALLBACK).status())
                .isEqualTo(OAuthOutcome.Status.USER_INFO_FAILED);
    }

    @Test
    void baseAddressesAreValidated() {
        assertThat(OAuthConfiguration.checkBase("", OAuthConfiguration.DEFAULT_QQ, "qq-base"))
                .isEqualTo("https://graph.qq.com/");
        for (String bad : new String[] {"ftp://x/", "https://graph.qq.com", "https://u:p@graph.qq.com/",
                "https://graph.qq.com/?a=1", "/relative/"}) {
            assertThatThrownBy(() -> OAuthConfiguration.checkBase(bad, OAuthConfiguration.DEFAULT_QQ, "qq-base"))
                    .as(bad).isInstanceOf(IllegalStateException.class);
        }
    }
}

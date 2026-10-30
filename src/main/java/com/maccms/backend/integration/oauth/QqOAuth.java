package com.maccms.backend.integration.oauth;

import com.maccms.backend.integration.application.OAuthOutcome;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import com.maccms.backend.integration.http.OutboundResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * QQ 互联网站应用的授权码流程（34 文档第 3.1 节）。旧 {@code extend/login} 缺失，按 QQ 互联公开文档与 ThinkSDK 的 {@code QqSDK} 实现：
 * <ol>
 * <li>授权页 {@code <base>oauth2.0/authorize?response_type=code&client_id&redirect_uri&state&scope=get_user_info}；</li>
 * <li>换令牌 POST {@code <base>oauth2.0/token}（{@code grant_type=authorization_code}、{@code client_id}、{@code client_secret}、
 * {@code code}、{@code redirect_uri}、{@code fmt=json}），响应可以是 JSON 或查询串；</li>
 * <li>取 openid GET {@code <base>oauth2.0/me?access_token&fmt=json}（JSON 或 JSONP），其中 {@code client_id} 必须是本应用；</li>
 * <li>取用户信息 GET {@code <base>user/get_user_info?access_token&oauth_consumer_key&openid&format=json}，{@code ret=0} 为成功。</li>
 * </ol>
 * 应用标识 {@code connect.qq.key}、密钥 {@code connect.qq.secret}（后台"整合登录配置"页，B15）。令牌、密钥、授权码不进日志。
 */
final class QqOAuth {

    private final OutboundHttpClient client;
    private final ThirdPartyConfig config;
    private final String base;

    QqOAuth(OutboundHttpClient client, ThirdPartyConfig config, String base) {
        this.client = client;
        this.config = config;
        this.base = base;
    }

    String authorizeUrl(String state, String callback) {
        return base + "oauth2.0/authorize?response_type=code&client_id=" + Form.enc(appId()) + "&redirect_uri="
                + Form.enc(callback) + "&state=" + Form.enc(state) + "&scope=get_user_info";
    }

    OAuthOutcome fetch(String code, String callback) {
        String appId = appId();
        String token;
        String openid;
        try {
            byte[] form = ("grant_type=authorization_code&client_id=" + Form.enc(appId) + "&client_secret="
                    + Form.enc(config.secret("connect", "qq", "secret").reveal()) + "&code=" + Form.enc(code)
                    + "&redirect_uri=" + Form.enc(callback) + "&fmt=json").getBytes(StandardCharsets.UTF_8);
            OutboundResponse r = client.send(OutboundRequest.post("oauth.qq.token", URI.create(base + "oauth2.0/token"),
                    "application/x-www-form-urlencoded", form));
            token = r.status() == 200 ? token(r.body()) : null;
            if (token == null) {
                return OAuthOutcome.tokenFailed();
            }
            OutboundResponse me = client.send(OutboundRequest.get("oauth.qq.me",
                    URI.create(base + "oauth2.0/me?access_token=" + Form.enc(token) + "&fmt=json")));
            JsonNode m = me.status() == 200 ? OAuthJson.object(me.body()) : null;
            openid = OAuthJson.text(m, "openid");
            // 令牌必须是发给本应用的
            if (!OAuthJson.validOpenid(openid) || !OAuthJson.text(m, "client_id").equals(appId)) {
                return OAuthOutcome.tokenFailed();
            }
        } catch (OutboundException e) {
            return OAuthOutcome.tokenFailed();
        }
        try {
            OutboundResponse r = client.send(OutboundRequest.get("oauth.qq.userinfo",
                    URI.create(base + "user/get_user_info?access_token=" + Form.enc(token) + "&oauth_consumer_key="
                            + Form.enc(appId) + "&openid=" + Form.enc(openid) + "&format=json")));
            JsonNode u = r.status() == 200 ? OAuthJson.object(r.body()) : null;
            if (u == null || !OAuthJson.zero(u, "ret", false)) {
                return OAuthOutcome.userInfoFailed(OAuthJson.text(u, "msg"));
            }
            return OAuthOutcome.ok(openid, OAuthJson.text(u, "nickname"));
        } catch (OutboundException e) {
            return OAuthOutcome.userInfoFailed("");
        }
    }

    /** 令牌响应：JSON {@code access_token}，或查询串 {@code access_token=…&expires_in=…}（旧 ThinkSDK 要求两项都有）；错误响应是 JSONP。 */
    static String token(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8).strip();
        if (s.startsWith("{") || s.startsWith("callback(")) {
            String t = OAuthJson.text(OAuthJson.object(body), "access_token");
            return t.isEmpty() ? null : t;
        }
        Map<String, String> q = Form.parse(s);
        String t = q.getOrDefault("access_token", "");
        return t.isEmpty() || q.getOrDefault("expires_in", "").isEmpty() ? null : t;
    }

    private String appId() {
        return config.secret("connect", "qq", "key").reveal();
    }
}

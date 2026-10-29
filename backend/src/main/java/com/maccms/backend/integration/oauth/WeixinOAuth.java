package com.maccms.backend.integration.oauth;

import com.maccms.backend.integration.application.OAuthOutcome;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import com.maccms.backend.integration.http.OutboundResponse;
import java.net.URI;
import tools.jackson.databind.JsonNode;

/**
 * 微信开放平台网站应用的授权码流程（34 文档第 3.2 节）。旧 {@code extend/login} 缺失，按微信开放平台公开文档实现：
 * <ol>
 * <li>授权页 {@code <open>connect/qrconnect?appid&redirect_uri&response_type=code&scope=snsapi_login&state#wechat_redirect}；</li>
 * <li>换令牌 GET {@code <api>sns/oauth2/access_token?appid&secret&code&grant_type=authorization_code}，响应 JSON 含
 * {@code access_token}、{@code openid}，失败时含 {@code errcode}；</li>
 * <li>取用户信息 GET {@code <api>sns/userinfo?access_token&openid}，没有 {@code errcode} 或为 0 时成功（旧 {@code LoginEvent::weixin}）；
 * 新版另要求其中的 {@code openid}（如有）与换令牌得到的一致。</li>
 * </ol>
 * 应用标识 {@code connect.weixin.key}、密钥 {@code connect.weixin.secret}。令牌、密钥、授权码不进日志（出站客户端只记主机）。
 */
final class WeixinOAuth {

    private final OutboundHttpClient client;
    private final ThirdPartyConfig config;
    private final String openBase;
    private final String apiBase;

    WeixinOAuth(OutboundHttpClient client, ThirdPartyConfig config, String openBase, String apiBase) {
        this.client = client;
        this.config = config;
        this.openBase = openBase;
        this.apiBase = apiBase;
    }

    String authorizeUrl(String state, String callback) {
        return openBase + "connect/qrconnect?appid=" + Form.enc(appId()) + "&redirect_uri=" + Form.enc(callback)
                + "&response_type=code&scope=snsapi_login&state=" + Form.enc(state) + "#wechat_redirect";
    }

    OAuthOutcome fetch(String code) {
        String token;
        String openid;
        try {
            OutboundResponse r = client.send(OutboundRequest.get("oauth.weixin.token",
                    URI.create(apiBase + "sns/oauth2/access_token?appid=" + Form.enc(appId()) + "&secret="
                            + Form.enc(config.secret("connect", "weixin", "secret").reveal()) + "&code=" + Form.enc(code)
                            + "&grant_type=authorization_code")));
            JsonNode t = r.status() == 200 ? OAuthJson.object(r.body()) : null;
            token = OAuthJson.text(t, "access_token");
            openid = OAuthJson.text(t, "openid");
            if (t == null || !OAuthJson.zero(t, "errcode", true) || token.isEmpty() || !OAuthJson.validOpenid(openid)) {
                return OAuthOutcome.tokenFailed();
            }
        } catch (OutboundException e) {
            return OAuthOutcome.tokenFailed();
        }
        try {
            OutboundResponse r = client.send(OutboundRequest.get("oauth.weixin.userinfo",
                    URI.create(apiBase + "sns/userinfo?access_token=" + Form.enc(token) + "&openid=" + Form.enc(openid))));
            JsonNode u = r.status() == 200 ? OAuthJson.object(r.body()) : null;
            if (u == null || !OAuthJson.zero(u, "errcode", true)) {
                return OAuthOutcome.userInfoFailed(OAuthJson.text(u, "errmsg"));
            }
            String infoOpenid = OAuthJson.text(u, "openid");
            if (!infoOpenid.isEmpty() && !infoOpenid.equals(openid)) {
                return OAuthOutcome.userInfoFailed("");
            }
            return OAuthOutcome.ok(openid, OAuthJson.text(u, "nickname"));
        } catch (OutboundException e) {
            return OAuthOutcome.userInfoFailed("");
        }
    }

    private String appId() {
        return config.secret("connect", "weixin", "key").reveal();
    }
}

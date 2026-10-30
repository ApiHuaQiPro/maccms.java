package com.maccms.backend.integration.oauth;

import com.maccms.backend.integration.application.OAuthLogin;
import com.maccms.backend.integration.application.OAuthOutcome;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OAuthLogin} 的实现（B26）：按旧配置 {@code connect.<type>} 分派给 {@link QqOAuth}、{@link WeixinOAuth}。
 * 每次换取记一行日志（方式、结果、耗时），不记授权码、令牌、openid、昵称。
 */
final class ConnectOAuthLogin implements OAuthLogin {

    private static final Logger log = LoggerFactory.getLogger(ConnectOAuthLogin.class);

    private final ThirdPartyConfig config;
    private final QqOAuth qq;
    private final WeixinOAuth weixin;

    ConnectOAuthLogin(ThirdPartyConfig config, QqOAuth qq, WeixinOAuth weixin) {
        this.config = config;
        this.qq = qq;
        this.weixin = weixin;
    }

    /** {@code connect.<type>.status} 为 {@code 1}，且 {@code key}、{@code secret} 都不为空（PHP {@code empty()}）。 */
    @Override
    public boolean enabled(String type) {
        return TYPES.contains(type) && config.text("connect", type, "status").equals("1")
                && !config.empty("connect", type, "key") && !config.empty("connect", type, "secret");
    }

    @Override
    public String authorizeUrl(String type, String state, String callback) {
        if (!enabled(type)) {
            throw new IllegalStateException("第三方登录方式未打开：" + type);
        }
        return type.equals("qq") ? qq.authorizeUrl(state, callback) : weixin.authorizeUrl(state, callback);
    }

    @Override
    public OAuthOutcome fetch(String type, String code, String callback) {
        if (!enabled(type)) {
            return OAuthOutcome.tokenFailed();
        }
        long start = System.nanoTime();
        OAuthOutcome out = type.equals("qq") ? qq.fetch(code, callback) : weixin.fetch(code);
        log.info("oauth fetch type={} result={} ms={}", type, out.status(), (System.nanoTime() - start) / 1_000_000);
        return out;
    }
}

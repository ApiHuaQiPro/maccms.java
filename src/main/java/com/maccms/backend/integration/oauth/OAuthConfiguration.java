package com.maccms.backend.integration.oauth;

import com.maccms.backend.integration.application.OAuthLogin;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第三方登录的装配（B26，34 文档第 3 节）。三个接口根地址是部署参数，默认是 QQ 互联、微信开放平台的公开地址：
 * <ul>
 * <li>{@code maccms.integration.oauth.qq-base}（{@code MACCMS_INTEGRATION_OAUTH_QQ_BASE}），默认 {@code https://graph.qq.com/}；</li>
 * <li>{@code maccms.integration.oauth.weixin-open-base}（{@code MACCMS_INTEGRATION_OAUTH_WEIXIN_OPEN_BASE}），默认
 * {@code https://open.weixin.qq.com/}（授权页，只给浏览器跳转）；</li>
 * <li>{@code maccms.integration.oauth.weixin-api-base}（{@code MACCMS_INTEGRATION_OAUTH_WEIXIN_API_BASE}），默认
 * {@code https://api.weixin.qq.com/}。</li>
 * </ul>
 * 必须是以 {@code /} 结尾、不带查询串与凭证的 http/https 地址，否则启动失败。应用标识与密钥取自旧配置 {@code connect.*}
 * （后台"整合登录配置"页，B15），重启生效（D-B23-5）。
 */
@Configuration(proxyBeanMethods = false)
public class OAuthConfiguration {

    static final String DEFAULT_QQ = "https://graph.qq.com/";
    static final String DEFAULT_WEIXIN_OPEN = "https://open.weixin.qq.com/";
    static final String DEFAULT_WEIXIN_API = "https://api.weixin.qq.com/";

    @Bean
    OAuthLogin oauthLogin(OutboundHttpClient client, ThirdPartyConfig config,
                          @Value("${maccms.integration.oauth.qq-base:" + DEFAULT_QQ + "}") String qq,
                          @Value("${maccms.integration.oauth.weixin-open-base:" + DEFAULT_WEIXIN_OPEN + "}") String open,
                          @Value("${maccms.integration.oauth.weixin-api-base:" + DEFAULT_WEIXIN_API + "}") String api) {
        return create(client, config, qq, open, api);
    }

    /** 按给定的配置与根地址创建（装配与测试共用；地址不合法时抛 {@link IllegalStateException}）。 */
    public static OAuthLogin create(OutboundHttpClient client, ThirdPartyConfig config, String qq, String weixinOpen,
                                    String weixinApi) {
        return new ConnectOAuthLogin(config,
                new QqOAuth(client, config, checkBase(qq, DEFAULT_QQ, "qq-base")),
                new WeixinOAuth(client, config, checkBase(weixinOpen, DEFAULT_WEIXIN_OPEN, "weixin-open-base"),
                        checkBase(weixinApi, DEFAULT_WEIXIN_API, "weixin-api-base")));
    }

    static String checkBase(String base, String fallback, String name) {
        String b = base == null || base.isBlank() ? fallback : base.strip();
        URI u;
        try {
            u = new URI(b);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("maccms.integration.oauth." + name + " 不是合法地址", null);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawQuery() != null
                || u.getRawFragment() != null || u.getRawUserInfo() != null || !b.endsWith("/")) {
            throw new IllegalStateException("maccms.integration.oauth." + name
                    + " 必须是以 / 结尾、不带查询串与凭证的 http/https 地址");
        }
        return b;
    }
}

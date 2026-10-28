package com.maccms.backend.integration.urlsend;

import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.application.UrlPush;
import com.maccms.backend.integration.http.OutboundHttpClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 百度推送的装配（B28）。接口地址 {@code maccms.integration.urlsend.baidu-endpoint}（环境变量
 * {@code MACCMS_INTEGRATION_URLSEND_BAIDU_ENDPOINT}），默认与旧版相同的 {@code http://data.zz.baidu.com/urls}；必须是不带查询串、
 * 不带用户名密码的 http/https 地址，否则启动失败。令牌照旧取自旧配置 {@code urlsend.baidu.token}、{@code urlsend.baidufast.token}
 * （后台"URL推送"页保存，B15），重启生效。
 */
@Configuration(proxyBeanMethods = false)
public class UrlsendConfiguration {

    static final String DEFAULT_ENDPOINT = "http://data.zz.baidu.com/urls";

    @Bean
    UrlPush urlPush(OutboundHttpClient client, ThirdPartyConfig config,
                    @Value("${maccms.integration.urlsend.baidu-endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint) {
        return new BaiduUrlPush(client, config, checkEndpoint(endpoint));
    }

    static String checkEndpoint(String endpoint) {
        String e = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.strip();
        URI u;
        try {
            u = new URI(e);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("maccms.integration.urlsend.baidu-endpoint 不是合法地址", null);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawQuery() != null
                || u.getRawFragment() != null || u.getRawUserInfo() != null) {
            throw new IllegalStateException("maccms.integration.urlsend.baidu-endpoint 必须是不带查询串与凭证的 http/https 地址");
        }
        return e;
    }
}

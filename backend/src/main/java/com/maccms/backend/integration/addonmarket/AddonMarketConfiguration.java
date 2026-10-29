package com.maccms.backend.integration.addonmarket;

import com.maccms.backend.integration.application.AddonMarket;
import com.maccms.backend.integration.http.OutboundHttpClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 插件市场的装配（B29）。
 * <ul>
 * <li>市场地址 {@code maccms.integration.addonmarket.api-url}（环境变量 {@code MACCMS_INTEGRATION_ADDONMARKET_API_URL}）：对应旧
 * {@code config('fastadmin.api_url')}。这份生产代码里没有配置它（{@code application/extra/} 下没有 {@code fastadmin.php}），所以默认留空：
 * 不发请求，安装、升级按旧版"无法下载远程文件"失败。填写时必须是不带查询串、片段与用户名密码的 http/https 地址（末尾的 {@code /} 去掉），
 * 否则启动失败。地址里不放令牌；市场账号的 {@code uid}/{@code token} 由后台按旧版从表单提交。</li>
 * <li>包大小上限 {@code maccms.integration.addonmarket.max-package-bytes}（{@code MACCMS_INTEGRATION_ADDONMARKET_MAX_PACKAGE_BYTES}），
 * 默认 16 MiB，1 KiB～256 MiB。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AddonMarketConfiguration {

    static final int DEFAULT_MAX_PACKAGE_BYTES = 16 * 1024 * 1024;
    static final int MIN_PACKAGE_BYTES = 1024;
    static final int MAX_PACKAGE_BYTES = 256 * 1024 * 1024;

    @Bean
    AddonMarket addonMarket(OutboundHttpClient client,
                            @Value("${maccms.integration.addonmarket.api-url:}") String apiUrl,
                            @Value("${maccms.integration.addonmarket.max-package-bytes:" + DEFAULT_MAX_PACKAGE_BYTES + "}") int maxBytes) {
        return new FastadminAddonMarket(client, checkApiUrl(apiUrl), checkMaxBytes(maxBytes));
    }

    static String checkApiUrl(String apiUrl) {
        String e = apiUrl == null ? "" : apiUrl.strip();
        if (e.isEmpty()) {
            return "";
        }
        URI u;
        try {
            u = new URI(e);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("maccms.integration.addonmarket.api-url 不是合法地址", null);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawQuery() != null
                || u.getRawFragment() != null || u.getRawUserInfo() != null) {
            throw new IllegalStateException("maccms.integration.addonmarket.api-url 必须是不带查询串与凭证的 http/https 地址");
        }
        while (e.endsWith("/")) {
            e = e.substring(0, e.length() - 1);
        }
        return e;
    }

    static int checkMaxBytes(int maxBytes) {
        if (maxBytes < MIN_PACKAGE_BYTES || maxBytes > MAX_PACKAGE_BYTES) {
            throw new IllegalStateException("maccms.integration.addonmarket.max-package-bytes 必须在 1 KiB～256 MiB 之间");
        }
        return maxBytes;
    }
}

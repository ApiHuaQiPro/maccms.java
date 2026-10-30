package com.maccms.backend.integration.sms;

import com.maccms.backend.integration.application.SmsSender;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短信发送的装配（B25，32 文档第 3 节）。两个通道的接口地址是部署参数，默认与旧版相同：
 * <ul>
 * <li>{@code maccms.integration.sms.aliyun-endpoint}（{@code MACCMS_INTEGRATION_SMS_ALIYUN_ENDPOINT}），默认
 * {@code http://dysmsapi.aliyuncs.com/}；</li>
 * <li>{@code maccms.integration.sms.qcloud-endpoint}（{@code MACCMS_INTEGRATION_SMS_QCLOUD_ENDPOINT}），默认
 * {@code https://yun.tim.qq.com/v5/tlssmssvr/sendsms}。</li>
 * </ul>
 * 必须是不带查询串与凭证的 http/https 地址，否则启动失败。账号、签名、模板、密钥照旧取自旧配置 {@code sms.*}（后台"短信配置"页保存，B15），
 * 重启生效（D-B23-5）。
 */
@Configuration(proxyBeanMethods = false)
public class SmsConfiguration {

    static final String DEFAULT_ALIYUN = "http://dysmsapi.aliyuncs.com/";
    static final String DEFAULT_QCLOUD = "https://yun.tim.qq.com/v5/tlssmssvr/sendsms";

    @Bean
    SmsSender smsSender(OutboundHttpClient client, ThirdPartyConfig config, ObjectProvider<Clock> clock,
                        @Value("${maccms.integration.sms.aliyun-endpoint:" + DEFAULT_ALIYUN + "}") String aliyun,
                        @Value("${maccms.integration.sms.qcloud-endpoint:" + DEFAULT_QCLOUD + "}") String qcloud) {
        return create(client, config, aliyun, qcloud, clock.getIfAvailable(Clock::systemUTC));
    }

    /** 按给定的配置与接口地址创建（装配与黄金复放测试共用；地址不合法时抛 {@link IllegalStateException}）。 */
    public static SmsSender create(OutboundHttpClient client, ThirdPartyConfig config, String aliyun, String qcloud, Clock clock) {
        SecureRandom random = new SecureRandom();
        return new LegacySmsSender(config,
                new AliyunSms(client, config, URI.create(checkEndpoint(aliyun, DEFAULT_ALIYUN, "aliyun-endpoint")), clock, random),
                new QcloudSms(client, config, checkEndpoint(qcloud, DEFAULT_QCLOUD, "qcloud-endpoint"), clock, random));
    }

    static String checkEndpoint(String endpoint, String fallback, String name) {
        String e = endpoint == null || endpoint.isBlank() ? fallback : endpoint.strip();
        URI u;
        try {
            u = new URI(e);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("maccms.integration.sms." + name + " 不是合法地址", null);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawQuery() != null
                || u.getRawFragment() != null || u.getRawUserInfo() != null) {
            throw new IllegalStateException("maccms.integration.sms." + name + " 必须是不带查询串与凭证的 http/https 地址");
        }
        return e;
    }
}

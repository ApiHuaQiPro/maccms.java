package com.maccms.backend.integration.mail;

import com.maccms.backend.integration.application.MailSender;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.http.OutboundProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 邮件发送的装配（B25，32 文档第 4 节）。SMTP 参数照旧取自旧配置 {@code email.*}（后台"邮件配置"页保存，B15），重启生效（D-B23-5）；
 * 出站模式与超时沿用出站客户端的部署参数 {@code maccms.integration.outbound.*}（{@code loopback-only} 时只能连回环地址）。
 * 信件的 {@code Date} 头按旧版时区 {@code PRC}。
 */
@Configuration(proxyBeanMethods = false)
public class MailConfiguration {

    @Bean
    MailSender mailSender(ThirdPartyConfig config, OutboundHttpClient client, OutboundProperties outbound,
                          ObjectProvider<Clock> clock) {
        Duration connect = outbound.connectTimeout() == null ? Duration.ofSeconds(15) : outbound.connectTimeout();
        Duration read = outbound.readTimeout() == null ? Duration.ofSeconds(30) : outbound.readTimeout();
        return create(config, client.mode(), connect, read, (SSLSocketFactory) SSLSocketFactory.getDefault(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /** 按给定的配置与出站参数创建（装配与黄金复放测试共用）。 */
    public static MailSender create(ThirdPartyConfig config, OutboundPolicy.Mode mode, Duration connect, Duration read,
                                    SSLSocketFactory tls, Clock clock) {
        return new LegacyMailSender(config, new SmtpMailer(mode, connect, read, tls, clock, ZoneId.of("Asia/Shanghai"),
                new SecureRandom()));
    }
}

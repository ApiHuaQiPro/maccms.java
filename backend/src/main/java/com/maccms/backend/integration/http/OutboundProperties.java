package com.maccms.backend.integration.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出站客户端的部署参数（B23，{@code maccms.integration.outbound.*}，26 文档第 3.3 节）。
 *
 * @param mode             {@code open}（默认）或 {@code loopback-only}（测试配置）
 * @param connectTimeout   连接超时，默认 15 秒（旧 {@code mac_curl_*} 的 {@code CURLOPT_CONNECTTIMEOUT}）
 * @param readTimeout      从发出请求到读完响应的总时限，默认 30 秒（旧 {@code CURLOPT_TIMEOUT}）
 * @param maxResponseBytes 响应体上限，默认 4 MiB
 */
@ConfigurationProperties(prefix = "maccms.integration.outbound")
public record OutboundProperties(String mode, Duration connectTimeout, Duration readTimeout, Integer maxResponseBytes) {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_MAX_RESPONSE_BYTES = 4 << 20;
}

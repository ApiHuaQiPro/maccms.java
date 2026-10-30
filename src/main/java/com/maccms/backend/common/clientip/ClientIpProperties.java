package com.maccms.backend.common.clientip;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客户端 IP 解析的部署参数 {@code maccms.client-ip.*}（B06，D2 ④，docs/architecture/16-b06-misc-pages-provide.md 第 2.1 节）。
 * 只来自本应用的环境变量，不是站点配置项。按原始字符串绑定，校验在 {@link ClientIpResolver#from} 中进行，错误只报参数名与项序号。
 *
 * @param trustedProxies 可信代理（{@code MACCMS_TRUSTED_PROXIES}）：逗号分隔的 IPv4/IPv6 地址或 CIDR，留空表示不信任任何代理
 * @param header         对端为可信代理时采用的转发头（{@code MACCMS_CLIENT_IP_HEADER}），留空为 {@code X-Forwarded-For}
 */
@ConfigurationProperties(prefix = "maccms.client-ip")
public record ClientIpProperties(String trustedProxies, String header) {
}

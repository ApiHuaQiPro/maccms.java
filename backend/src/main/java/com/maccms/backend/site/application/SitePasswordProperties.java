package com.maccms.backend.site.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 访问密码签名票据的部署密钥 {@code maccms.site.password-tickets.secret}（B05，D-B05-3）。只来自本应用的环境变量
 * {@code MACCMS_SITE_PASSWORD_SECRET}，没有内置值、不取旧配置；留空时不提供在线密码验证。按原始字符串绑定，校验在
 * {@link PasswordTickets#from} 中进行，错误只报配置项名称。
 *
 * @param secret 部署密钥
 */
@ConfigurationProperties(prefix = "maccms.site.password-tickets")
public record SitePasswordProperties(String secret) {

    @Override
    public String toString() {
        return "SitePasswordProperties[]";
    }
}

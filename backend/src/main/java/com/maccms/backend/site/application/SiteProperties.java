package com.maccms.backend.site.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * site 模块的外部化配置 {@code maccms.site.*}，是站点设置的唯一来源（04 报告第 6 节、P4）。
 * 值来自本应用的环境变量（见 application.yml 与 config/maccms-backend.env.example）；配置了旧配置目录时，留空的项由装配类按字段
 * 取旧配置 {@code extra/maccms.php} 的值（{@link SiteLegacyConfig}，B02），site 自己不读文件。
 *
 * <p>全部按原始字符串绑定，绑定本身不会失败，所以 Spring 的绑定错误分析不会把取值打印到启动日志；
 * 校验与默认值统一在 {@link SiteSettings#from(SiteProperties)} 中进行，错误只报配置项名称。
 * {@code enabled} 的解析见 {@code site.config.SiteEnabledCondition}。
 *
 * @param enabled           是否注册 site 页面路由，默认 false
 * @param name              站名，启用时必填（旧 site.site_name，application/extra/maccms.php:20，只作参照）
 * @param baseUrl           站点根 URL，启用时必填，canonical 只用它（旧 site_url + install_dir，:21,31）
 * @param keywords          站点关键词（旧 site_keywords，:23）
 * @param description       站点描述（旧 site_description，:24）
 * @param icp               备案号文本（旧 site_icp，:25）
 * @param logoUrl           Logo 地址（旧 site_logo，:32）
 * @param status            open / closed，留空按 open（旧 site_status，:40）
 * @param closedMessage     维护页文案，按文本转义输出（旧 site_close_tip，:41）
 * @param canonicalRedirect canonical 301，D4 未批准，只接受 false
 * @param hitCounterEnabled 点击计数（ajax/hits 隐藏图片请求，B11 起可打开）：true 或 false，默认关闭（03 方案第 6.3 节）
 */
@ConfigurationProperties(prefix = "maccms.site")
public record SiteProperties(String enabled, String name, String baseUrl, String keywords, String description,
                             String icp, String logoUrl, String status, String closedMessage,
                             String canonicalRedirect, String hitCounterEnabled) {
}

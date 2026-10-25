package com.maccms.backend.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台部署参数（B12，21 文档第 7 节；{@code maccms.admin.*}，只经环境变量提供，不是站点配置项）。
 *
 * @param enabled 是否注册后台路由，默认关闭
 * @param entry   后台入口文件的路径（旧版"后台入口文件改名"，如 {@code /_admin.php}）：必填，单段 {@code /名称.php}，不能是
 *                {@code /admin.php}（旧入口拒绝这个名字，_admin.php:30-33）与前台、接口、安装入口的文件名
 * @param baseUrl 对外地址（与 {@code maccms.site.base-url} 相同的写法，路径以 / 结尾）：决定页面里的站内路径前缀、会话 cookie 的
 *                Path 与 Secure，以及表单提交的来源校验
 */
@ConfigurationProperties(prefix = "maccms.admin")
public record AdminProperties(String enabled, String entry, String baseUrl) {
}

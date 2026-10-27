package com.maccms.backend.sitetemplate.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 前台模板在线编辑的部署参数（{@code maccms.site-template.*}，24 文档第 7.1 节）。
 *
 * @param enabled 开启后前台改用外部模板目录 {@code template/<site.template_dir>/<site.html_dir>/} 的编译结果（第一次开启时从内置版本导出），
 *                后台可以在线编辑 jte 模板；默认关闭，前台只用 jar 内预编译的模板
 * @param workDir 运行时编译的工作目录（生成的源码与类；可执行 jar 时还有解出的类路径）；留空时每次启动在系统临时目录下新建
 */
@ConfigurationProperties("maccms.site-template")
public record SiteTemplateProperties(boolean enabled, String workDir) {
}

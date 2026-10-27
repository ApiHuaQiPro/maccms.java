package com.maccms.backend.site.application;

import gg.jte.TemplateEngine;

/**
 * 在线编辑的外部模板（B15，路线图第 5.6 节，24 文档第 7 节）：由 sitetemplate 模块实现。前台每次渲染页面前取一次当前引擎——
 * 外部模板目录整体编译通过时是只加载那次编译结果的引擎，否则为 null，前台用 jar 内预编译的内置模板。
 * 引擎只在后台保存成功（或启动编译成功）时整体替换：新请求用新版本，进行中的请求在取到的那一版上完成。
 */
public interface SiteTemplateSource {

    /** 当前生效的外部模板引擎；null 表示使用内置模板。 */
    TemplateEngine current();
}

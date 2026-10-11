package com.maccms.backend.site.application;

/**
 * 旧站 URL 形态配置（只含本批支持的分支）。规则与黄金向量见 docs/frontend/02-url-golden-vectors.md。
 *
 * <p>对应旧配置：{@code rewrite.status}（application/extra/maccms.php:490）、{@code rewrite.suffix_hide}（:488）、
 * {@code rewrite.route_status}（:489，开启时使用仓库 application/route.php 的路由表）、{@code app.pathinfo_depr}（:47）。
 * 以下前提固定，不可配置：{@code site.install_dir='/'}、{@code rewrite.type_id=0}/{@code vod_id=0}（数字 id）、
 * {@code view.*=0}（动态页，非静态化）、ThinkPHP {@code url_html_suffix} 首项 {@code html}（application/config.php:86）。
 * 线上实际取值未核实；本类只提供逻辑，暂不接入外部化配置。
 *
 * @param rewrite      rewrite.status=1：生成的地址不含 {@code index.php}（能否到达 PHP 取决于部署转发）
 * @param suffixHide   rewrite.suffix_hide=1：{@code .html} 替换为 {@code /}
 * @param routeAliases rewrite.route_status=1，且路由表为仓库默认 application/route.php
 * @param depr         app.pathinfo_depr，{@code '/'} 或 {@code '-'}
 */
public record LegacyUrlStyle(boolean rewrite, boolean suffixHide, boolean routeAliases, char depr) {

    /** 仓库默认：pathinfo_depr=/、rewrite.status=0、route_status=0、suffix_hide=0。 */
    public static final LegacyUrlStyle REPO_DEFAULT = new LegacyUrlStyle(false, false, false, '/');

    public LegacyUrlStyle {
        if (depr != '/' && depr != '-') {
            throw new IllegalArgumentException("pathinfo_depr 只支持 / 或 -");
        }
        if (routeAliases && depr != '/') {
            // 路由表按 / 书写，"-" 分隔符下的匹配与生成没有向量覆盖，不支持
            throw new IllegalArgumentException("route_status=1 只支持 pathinfo_depr=/");
        }
    }
}

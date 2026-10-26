package com.maccms.backend.site.web.view;

import com.maccms.backend.common.lang.LegacyLang;

/**
 * 本次请求的手机模板与语言选择（B12 承接 B05 D-B05-8，docs/architecture/21-b12-admin-framework.md 第 12 节）。由
 * {@code site.web.SiteVariants} 按旧 {@code common/behavior/Init.php} 的语义（{@code site.application.SiteTemplateSelection}）在每个请求
 * 开始时算出，页面外壳经 {@link PageChrome#variant()} 拿到。
 *
 * <p>新版前台是一套响应式 jte 模板，没有旧版那样的手机模板目录；{@code wap} 只决定外壳里随手机切换的输出（手机 Logo）与缓存的
 * {@code Vary}，{@code pathTpl}、{@code pathAds} 是旧模板变量的值，供以后需要时使用（新版页面不引用旧模板目录里的文件，也不输出广告位）。
 *
 * @param wap     旧 {@code MAC_MOB}：手机访问、未命中站群，且 {@code mob_status} 为 2，或为 1 且 {@code Host} 等于 {@code site_wapurl}
 * @param domain  命中了站群（{@code extra/domain.php} 里有本次 {@code Host} 的非空项）
 * @param lang    语言包（旧 {@code app.lang}，不按请求切换）
 * @param pathTpl 旧 {@code {$maccms.path_tpl}}（没有旧配置时为空串）
 * @param pathAds 旧 {@code {$maccms.path_ads}}（没有旧配置时为空串）
 */
public record SiteVariant(boolean wap, boolean domain, LegacyLang lang, String pathTpl, String pathAds) {

    /** 没有旧配置、或请求之外（单元测试直接组装视图模型）时：桌面、简体。 */
    public static final SiteVariant DEFAULT = new SiteVariant(false, false, LegacyLang.of(LegacyLang.DEFAULT_CODE), "", "");

    /** 语言代码：{@code zh-cn} 或 {@code zh-tw}。 */
    public String langCode() {
        return lang.code();
    }
}

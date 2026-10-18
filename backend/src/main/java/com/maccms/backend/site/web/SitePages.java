package com.maccms.backend.site.web;

import com.maccms.backend.site.application.BrowserAddress;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.ErrorPage;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.PageChrome;
import com.maccms.backend.site.web.view.PlaceholderPage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 由站点设置组装页面外壳与错误页视图模型。错误页不带 canonical、description、keywords、分类导航，一律 noindex，
 * 组装时不读取任何内容数据（维护页、503 页不查库）。
 */
public class SitePages {

    /** 骨架占位页的站内路径（相对站点根）。 */
    public static final String PLACEHOLDER_PATH = "_site/placeholder";
    /** 样式表（src/main/resources/static/site/site.css）的站内路径。 */
    static final String STYLESHEET_PATH = "site/site.css";
    /** 播放器内页的引导脚本（src/main/resources/static/site-player/player-boot.js，Java 维护）。 */
    static final String PLAYER_BOOT_PATH = "site-player/player-boot.js";
    /** 由旧配置生成的播放器配置脚本（PlayerConfigController，旧路径）。 */
    static final String PLAYER_CONFIG_PATH = "static/js/playerconfig.js";
    /** 试看计时脚本（src/main/resources/static/site/trial.js，前端维护，MACC-54）。 */
    static final String TRIAL_SCRIPT_PATH = "site/trial.js";

    private final SiteSettings site;
    private final SafeUrl searchAction;
    private final SafeUrl loginUrl;
    private final Optional<SafeUrl> logo;

    public SitePages(SiteSettings site, LegacyUrlBuilder urls) {
        this(site, urls, true);
    }

    /**
     * @param externalLogo 浏览器侧外部地址放行 W2（B05，K5）：关闭时 Logo 是外部主机（不是 base-url 的主机）的绝对地址就不输出，
     *                     页眉只显示站名
     */
    public SitePages(SiteSettings site, LegacyUrlBuilder urls, boolean externalLogo) {
        this.site = site;
        String siteHost = BrowserAddress.hostOf(site.baseUrl());
        this.logo = site.logo().filter(l -> externalLogo
                || BrowserAddress.classify(l.href(), siteHost).kind() == BrowserAddress.Kind.LOCAL);
        // 登录仍归旧 PHP（03 方案第 3.1、6.1 节）：只是页面级链接，不调用 ajax/suggest 等接口。搜索表单地址不变
        // （旧 vod/search 基址）：maccms.site.search.enabled=true 时由 Java 搜索页处理，否则仍归 PHP（09 方案第 7.1 节）
        this.searchAction = site.publicPath(urls.vodSearch(Map.of(), 1));
        this.loginUrl = site.publicPath(urls.user("login"));
    }

    public SiteSettings site() {
        return site;
    }

    public PlaceholderPage placeholder() {
        return new PlaceholderPage(chrome("站点骨架占位页 - " + site.name(), site.description(), site.keywords(),
                Optional.of(site.canonical(PLACEHOLDER_PATH)), true, List.of()));
    }

    public ErrorPage notFound() {
        return error("页面不存在", "您访问的页面不存在或已被移除。");
    }

    /** 旧 {@code error(lang('illegal_request'))}（自定义标签页不存在，index/controller/Label.php:19-21，B06）。 */
    public ErrorPage illegalRequest() {
        return error("非法请求", "请求的页面不存在。");
    }

    public ErrorPage serverError() {
        return error("页面暂时无法显示", "服务器处理请求时出错，请稍后再试。");
    }

    public ErrorPage unavailable() {
        return error("服务暂时不可用", "请稍后再试。");
    }

    public ErrorPage closed() {
        return error("站点维护中", site.closedMessage());
    }

    /** 旧搜索频率限制的提示（{@code index/controller/Base.php:59-62}，B08）。 */
    public ErrorPage tooFrequent(String message) {
        return error("请稍后再试", message);
    }

    /**
     * 内容页外壳。
     *
     * @param fullTitle   完整的 title 文本（调用方负责拼接站名）
     * @param description meta description，空串时用站点描述
     * @param keywords    meta keywords，空串时用站点关键词
     * @param canonical   canonical 绝对地址（只由 base-url 生成）
     * @param nav         顶级分类导航
     */
    PageChrome content(String fullTitle, String description, String keywords, SafeUrl canonical, List<NavLink> nav) {
        return chrome(fullTitle, description.isEmpty() ? site.description() : description,
                keywords.isEmpty() ? site.keywords() : keywords, Optional.of(canonical), false, nav);
    }

    /**
     * 搜索页外壳（MACC-20）：一律 noindex；description/keywords 用站点值。
     *
     * @param canonical 自引用 canonical；参数不合法的 400 页没有
     * @param nav       顶级分类导航；不查库的页面（无条件、400）为空
     */
    PageChrome search(String fullTitle, Optional<SafeUrl> canonical, List<NavLink> nav) {
        return chrome(fullTitle, site.description(), site.keywords(), canonical, true, nav);
    }

    private ErrorPage error(String heading, String message) {
        return new ErrorPage(chrome(heading + " - " + site.name(), "", "", Optional.empty(), true, List.of()),
                heading, message);
    }

    private PageChrome chrome(String fullTitle, String description, String keywords, Optional<SafeUrl> canonical,
                              boolean noindex, List<NavLink> nav) {
        return new PageChrome(fullTitle, site.name(), site.homePath(), site.publicPath(STYLESHEET_PATH), logo,
                site.icp(), description, keywords, canonical, noindex, List.copyOf(nav), searchAction, loginUrl);
    }
}

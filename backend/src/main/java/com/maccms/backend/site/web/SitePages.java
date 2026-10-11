package com.maccms.backend.site.web;

import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.ErrorPage;
import com.maccms.backend.site.web.view.PageChrome;
import com.maccms.backend.site.web.view.PlaceholderPage;
import java.util.Optional;

/** 由站点设置组装页面视图模型。错误页不带 canonical、description、keywords，一律 noindex。 */
public class SitePages {

    /** 骨架占位页的站内路径（相对站点根）。 */
    public static final String PLACEHOLDER_PATH = "_site/placeholder";
    /** 样式表（src/main/resources/static/site/site.css）的站内路径。 */
    static final String STYLESHEET_PATH = "site/site.css";

    private final SiteSettings site;

    public SitePages(SiteSettings site) {
        this.site = site;
    }

    public PlaceholderPage placeholder() {
        return new PlaceholderPage(chrome("站点骨架占位页", Optional.of(site.canonical(PLACEHOLDER_PATH)), true));
    }

    public ErrorPage notFound() {
        return error("页面不存在", "您访问的页面不存在或已被移除。");
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

    private ErrorPage error(String heading, String message) {
        PageChrome chrome = new PageChrome(heading + " - " + site.name(), site.name(), site.homePath(),
                site.publicPath(STYLESHEET_PATH), site.logo(), site.icp(), "", "", Optional.empty(), true);
        return new ErrorPage(chrome, heading, message);
    }

    private PageChrome chrome(String pageTitle, Optional<SafeUrl> canonical, boolean noindex) {
        return new PageChrome(pageTitle + " - " + site.name(), site.name(), site.homePath(),
                site.publicPath(STYLESHEET_PATH), site.logo(), site.icp(), site.description(), site.keywords(), canonical,
                noindex);
    }
}

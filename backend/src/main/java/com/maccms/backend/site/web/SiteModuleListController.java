package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LibrarySearchService;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.ModuleListPage;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.SiteFeatures;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.site.web.view.ModuleListView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 文章、专题、演员、角色、网址、剧情的筛选页（旧 show）、搜索页（旧 search）与 ajax 片段（B05，队长裁定 D-B05-5，15 文档第 7.2 节）。
 * GET/HEAD。解析见 {@link LegacyUrlParser#parseModuleList}，页面见 {@link ModuleListPages}。
 *
 * <p>开关（与旧 Base.php:27-79 的 check_show/check_ajax/check_search 对应）：搜索页随视频搜索页
 * （{@code maccms.site.search.enabled}，旧 app.search）；筛选页随旧 {@code app.show}（{@link SiteFeatures#show()}，默认关闭）；
 * ajax 入口另需旧 {@code app.ajax_page}。关闭时 404，不查库。旧版的搜索频率限制（search_timespan）与验证码（search_verify、
 * show_verify）新版不提供（15 文档第 11 节）。
 *
 * <p>状态：不认识的形态、分类不存在或不可见、越界页 → 404；参数不合法 → 400（固定提示页或空片段，不回显输入）；
 * 503/500 与站点关闭沿用 SiteExceptionHandler 与 SiteStatusInterceptor。页面 noindex、no-store。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteModuleListController {

    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final ModuleListPages pages;
    private final SiteFeatures features;
    private final boolean searchEnabled;

    public SiteModuleListController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                    LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                    ContentReadService content, ArticleReadService articles, TopicReadService topics,
                                    ActorReadService actors, RoleReadService roles, WebsiteReadService websites,
                                    VideoPlotReadService plots, LibrarySearchService search, SiteFeatures features,
                                    Environment environment) {
        this.renderer = renderer;
        this.parser = parser;
        this.features = features;
        this.searchEnabled = SiteSearchEnabledCondition.isEnabled(
                environment.getProperty(SiteSearchEnabledCondition.PROPERTY));
        LibraryPages library = new LibraryPages(articles, topics, actors, roles, websites, plots, content, sitePages,
                urls, images, richText, false);
        this.pages = new ModuleListPages(library, search, articles, actors, websites, content, sitePages, urls, images);
    }

    @GetMapping({
        "/index.php/art/search", "/index.php/art/search.html", "/index.php/art/search/",
        "/index.php/topic/search", "/index.php/topic/search.html", "/index.php/topic/search/",
        "/index.php/actor/search", "/index.php/actor/search.html", "/index.php/actor/search/",
        "/index.php/role/search", "/index.php/role/search.html", "/index.php/role/search/",
        "/index.php/website/search", "/index.php/website/search.html", "/index.php/website/search/",
        "/index.php/plot/search", "/index.php/plot/search.html", "/index.php/plot/search/",
        "/index.php/art/ajax_search", "/index.php/art/ajax_search.html", "/index.php/art/ajax_search/",
        "/index.php/topic/ajax_search", "/index.php/topic/ajax_search.html", "/index.php/topic/ajax_search/",
        "/index.php/actor/ajax_search", "/index.php/actor/ajax_search.html", "/index.php/actor/ajax_search/",
        "/index.php/role/ajax_search", "/index.php/role/ajax_search.html", "/index.php/role/ajax_search/",
        "/index.php/website/ajax_search", "/index.php/website/ajax_search.html", "/index.php/website/ajax_search/",
        "/index.php/plot/ajax_search", "/index.php/plot/ajax_search.html", "/index.php/plot/ajax_search/",
        "/art/search", "/art/search.html", "/art/search/", "/topic/search", "/topic/search.html", "/topic/search/",
        "/actor/search", "/actor/search.html", "/actor/search/", "/role/search", "/role/search.html", "/role/search/",
        "/website/search", "/website/search.html", "/website/search/", "/plot/search", "/plot/search.html",
        "/plot/search/", "/art/ajax_search", "/art/ajax_search.html", "/art/ajax_search/", "/topic/ajax_search",
        "/topic/ajax_search.html", "/topic/ajax_search/", "/actor/ajax_search", "/actor/ajax_search.html",
        "/actor/ajax_search/", "/role/ajax_search", "/role/ajax_search.html", "/role/ajax_search/",
        "/website/ajax_search", "/website/ajax_search.html", "/website/ajax_search/", "/plot/ajax_search",
        "/plot/ajax_search.html", "/plot/ajax_search/",
        "/index.php/art/show/**", "/index.php/actor/show/**", "/index.php/website/show/**",
        "/index.php/art/ajax_show/**", "/index.php/actor/ajax_show/**", "/index.php/website/ajax_show/**",
        "/index.php/actor/show.html", "/index.php/actor/ajax_show.html",
        "/index.php/role/show/**", "/index.php/role/ajax_show/**", "/index.php/role/show.html",
        "/index.php/role/ajax_show.html",
        "/art/show/**", "/actor/show/**", "/website/show/**", "/art/ajax_show/**", "/actor/ajax_show/**",
        "/website/ajax_show/**", "/actor/show.html", "/actor/ajax_show.html", "/role/show/**", "/role/ajax_show/**",
        "/role/show.html", "/role/ajax_show.html"})
    public ResponseEntity<String> list(HttpServletRequest request) {
        // getRequestURI / getQueryString 都是未解码的原始值；应用挂在根路径（base-url 子目录由反向代理映射）
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        ModuleListPage requested = requested(rawPath);
        if (requested == null || (requested.show() ? !features.show() : !searchEnabled)
                || (requested.ajax() && !features.ajaxPage())) {
            // 旧版 app.show/app.search/app.ajax_page 关闭时报"…功能关闭中"（Base.php:27-79）；新版不提供该入口
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        ModuleListView view;
        HttpStatus status;
        try {
            LegacyUrlParser.ModuleMatch m = parser.parseModuleList(rawPath, request.getQueryString())
                    .filter(x -> x.route().page() == requested)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"));
            // 先组装视图模型（读取数据）再渲染：读取失败的业务异常（404/503）不能被渲染器包装成 500
            view = pages.render(m.route());
            status = HttpStatus.OK;
        } catch (ApiException e) {
            if (e.code() != ErrorCode.INVALID_PARAMETER) {
                throw e;
            }
            view = pages.invalid(requested);
            status = HttpStatus.BAD_REQUEST;
        }
        ModuleListView v = view;
        return requested.ajax() ? renderer.noStore(status, tpl -> tpl.siteAjaxModuleList(v))
                : renderer.noStore(status, tpl -> tpl.sitePagesModuleList(v));
    }

    /** 路径对应的页面（只看 模块/操作 两段，参数由解析器判断）。 */
    private static ModuleListPage requested(String rawPath) {
        String p = rawPath.startsWith("/index.php/") ? rawPath.substring("/index.php/".length()) : rawPath.substring(1);
        String[] seg = p.split("/", 3);
        if (seg.length < 2) {
            return null;
        }
        String action = seg[1].endsWith(".html") ? seg[1].substring(0, seg[1].length() - ".html".length()) : seg[1];
        return ModuleListPage.byTarget(seg[0] + "/" + action);
    }
}

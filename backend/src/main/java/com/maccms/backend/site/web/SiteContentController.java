package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首批三页（MACC-14）：首页、分类列表（vod/type）、视频公开详情（vod/detail），只承接数字 id。GET/HEAD。
 *
 * <p>路由（03 方案第 5.3 节的白名单）：{@code /}、{@code /index.php}，以及带/不带 {@code index.php} 的
 * {@code vod/type}、{@code vod/detail} 路径；具体是哪一页、参数是否合法、是不是规范地址，全部由已验收的
 * {@link LegacyUrlParser} 按配置的 URL 形态判定（输入是原始请求路径与查询串，不读 Host、X-Forwarded-*）。
 * 解析器不认识的形态（拼音/编码 id、当前配置下不存在的形态等）按"交还 PHP"处理——交还机制未批准，本地返回 404；
 * 认得但参数不合法（越界页码、未知参数、查询串参数等）→ INVALID_PARAMETER → 404（SiteExceptionHandler）。
 * 非规范形态只输出 canonical，不做 301（D4 未批准）。路由别名（vodtype/…）与 "-" 分隔符形态不注册。
 *
 * <p>站点关闭时 SiteStatusInterceptor 在进入本 Controller 之前返回 503，不查库。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteContentController {

    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final ContentPages pages;
    private final ObjectProvider<SiteSearchController> search;
    private final ObjectProvider<SitePlaybackController> playback;
    private final ObjectProvider<SiteLibraryController> library;
    private final ObjectProvider<SiteMiscController> misc;

    public SiteContentController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                 LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                 ContentReadService content, ObjectProvider<SiteSearchController> search,
                                 ObjectProvider<SitePlaybackController> playback,
                                 ObjectProvider<SiteLibraryController> library,
                                 ObjectProvider<SiteMiscController> misc) {
        this.renderer = renderer;
        this.parser = parser;
        this.pages = new ContentPages(content, sitePages, urls, images, richText);
        this.search = search;
        this.playback = playback;
        this.library = library;
        this.misc = misc;
    }

    @GetMapping({"/", "/index.php", "/index.php/",
            "/index.php/vod/type/**", "/index.php/vod/detail/**", "/vod/type/**", "/vod/detail/**"})
    public ResponseEntity<String> page(HttpServletRequest request, HttpServletResponse response) {
        // getRequestURI 是未解码的原始路径；应用挂在根路径（base-url 子目录由反向代理映射，05 文档第 2 节）
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        // ThinkPHP 兼容模式 ?s=vod/search&…（B05）：入口路径上指向搜索页的 s 由搜索页处理（搜索页未开启时 404）。
        // parseSearch 在入口路径上只有认出指向搜索页的 s 之后才会抛参数错误，此时同样交给搜索页（显示 400 搜索错误页）
        if (request.getQueryString() != null && request.getQueryString().contains("s=")) {
            SiteSearchController s = search.getIfAvailable();
            if (s != null && isCompatSearch(rawPath, request.getQueryString())) {
                return s.search(request, response);
            }
        }
        SiteRoute route = parser.parse(rawPath, request.getQueryString())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"))
                .route();
        // 先组装视图模型（读取数据）再渲染：读取失败的业务异常（404/503）不能被渲染器包装成 500
        return switch (route) {
            case SiteRoute.Home h -> {
                var page = pages.home();
                yield renderer.ok(tpl -> tpl.sitePagesHome(page), false);
            }
            case SiteRoute.VodType t -> {
                var page = pages.category(t);
                yield renderer.ok(tpl -> tpl.sitePagesCategory(page), false);
            }
            case SiteRoute.VodDetail d -> {
                var page = pages.detail(d);
                yield renderer.ok(tpl -> tpl.sitePagesDetail(page), false);
            }
            // parse() 不认识 vod/search；搜索页只由 SiteSearchController 经 parseSearch 承接
            case SiteRoute.VodSearch s -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
            // 分类筛选页只由 SiteVodShowController 经 parseVodList 承接
            case SiteRoute.VodShow s -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
            // 模块筛选/搜索页只由 SiteModuleListController 经 parseModuleList 承接
            case SiteRoute.ModuleList m -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
            // B05 的其他页面经 ?s= 兼容模式到达入口路径时，交给承接该页面的 Controller（它们按同一请求重新解析）
            case SiteRoute.Legacy l -> l.page().episode() ? playback.getObject().page(request)
                    : l.page().misc() ? misc.getObject().page(request) : library.getObject().page(request);
        };
    }

    private boolean isCompatSearch(String rawPath, String rawQuery) {
        try {
            return parser.parseSearch(rawPath, rawQuery).isPresent();
        } catch (ApiException e) {
            if (e.code() != ErrorCode.INVALID_PARAMETER) {
                throw e;
            }
            return true;
        }
    }
}

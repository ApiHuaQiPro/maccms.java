package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.SiteFeatures;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.site.web.view.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 分类筛选页（旧 {@code vod/show}，Vod.php:23-36；B05，队长裁定 D-B05-5、D-B05-7）。GET/HEAD。
 *
 * <p>入口：{@code vod/show/id/<分类>[/键/值…]}（旧 mac_url_type 的路径参数形式）、{@code vodshow/<12 位>}（旧路由的位置参数形式），
 * 都可再带查询串；{@code vod/ajax_show/…} 输出同一结果的 HTML 片段。规范地址为 {@code vod/show/id/<分类>.html?<条件>}
 * （{@link LegacyUrlBuilder#vodShowLink}），其余形态只输出 canonical。解析见 {@link LegacyUrlParser#parseVodList}。
 *
 * <p>开关：旧 {@code app.show}（{@link SiteFeatures#show()}，默认关闭）关闭时全部 404；ajax_show 另需 {@code app.ajax_page}。
 * 与搜索页（{@code maccms.site.search.enabled}）互不依赖；包含匹配条件仍受 {@code maccms.content.search.enabled} 约束（关闭时 400）。
 * 参数不合法 → 400（与搜索页相同的固定提示页，不回显输入）；分类不存在、不可见或格式不对 → 404。页面 noindex、no-store。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteVodShowController {

    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final SearchPages pages;
    private final SiteFeatures features;

    public SiteVodShowController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                 LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                 ContentReadService content, SiteFeatures features) {
        this.renderer = renderer;
        this.parser = parser;
        this.features = features;
        this.pages = new SearchPages(content, sitePages, urls, new ContentPages(content, sitePages, urls, images,
                richText));
    }

    @GetMapping({"/index.php/vod/show/**", "/vod/show/**", "/index.php/vodshow/**", "/vodshow/**",
            "/index.php/vod/ajax_show/**", "/vod/ajax_show/**"})
    public ResponseEntity<String> show(HttpServletRequest request) {
        if (!features.show()) {
            // 旧版 app.show=0 时报"筛选页功能关闭中"（Base.php:27-31）；新版不提供该入口
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        boolean ajax = rawPath.contains("/ajax_show/");
        if (ajax && !features.ajaxPage()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        SearchPage page;
        HttpStatus status;
        try {
            LegacyUrlParser.ListMatch m = parser.parseVodList(rawPath, request.getQueryString())
                    .filter(x -> x.route() instanceof SiteRoute.VodShow && x.ajax() == ajax)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"));
            // 先组装视图模型（读取数据）再渲染：读取失败的业务异常（404/503）不能被渲染器包装成 500
            page = pages.show((SiteRoute.VodShow) m.route());
            status = HttpStatus.OK;
        } catch (ApiException e) {
            if (e.code() != ErrorCode.INVALID_PARAMETER) {
                throw e;
            }
            page = pages.invalid();
            status = HttpStatus.BAD_REQUEST;
        }
        SearchPage view = page;
        return ajax ? renderer.noStore(status, tpl -> tpl.siteAjaxVideoList(view))
                : renderer.noStore(status, tpl -> tpl.sitePagesSearch(view));
    }
}

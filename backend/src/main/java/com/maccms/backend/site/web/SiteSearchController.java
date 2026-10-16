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
import com.maccms.backend.site.web.view.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SSR 搜索页（MACC-20，09 方案第 7 节）：旧 {@code vod/search} 的固定路径 + 查询串形式。GET/HEAD。
 * 只在 {@code maccms.site.enabled=true} 且 {@code maccms.site.search.enabled=true} 时注册；关闭时这些路径在 Java 中不存在。
 *
 * <p>只注册 {@code /index.php/vod/search}、{@code .html}、{@code /} 三种与去掉 {@code /index.php} 的三种（后者只在
 * {@code rewrite} 时被解析器认可），不注册 {@code /vod/search/**}：路径参数形式归 PHP。参数由
 * {@link LegacyUrlParser#parseSearch} 从原始请求路径与查询串解析（单次解码，不调用 getParameter，不读 Host、X-Forwarded-*）。
 *
 * <p>状态：不认识的形态 → 404；参数不合法（含包含匹配开关关闭时的 wd/class）→ 400 搜索错误页（固定文案、空白表单、
 * 不回显输入）；越界页 → 404；503/500 与站点关闭沿用 SiteExceptionHandler 与 SiteStatusInterceptor。
 * 所有搜索页 noindex、no-store；日志不记录关键字或查询串。
 */
@Controller
@ConditionalOnSiteEnabled
@Conditional(SiteSearchEnabledCondition.class)
public class SiteSearchController {

    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final SearchPages pages;

    public SiteSearchController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                ContentReadService content) {
        this.renderer = renderer;
        this.parser = parser;
        this.pages = new SearchPages(content, sitePages, urls, new ContentPages(content, sitePages, urls, images,
                richText));
    }

    @GetMapping({"/index.php/vod/search", "/index.php/vod/search.html", "/index.php/vod/search/",
            "/vod/search", "/vod/search.html", "/vod/search/"})
    public ResponseEntity<String> search(HttpServletRequest request) {
        // getRequestURI / getQueryString 都是未解码的原始值；应用挂在根路径（base-url 子目录由反向代理映射）
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        SearchPage page;
        HttpStatus status;
        try {
            SiteRoute route = parser.parseSearch(rawPath, request.getQueryString())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"))
                    .route();
            // 先组装视图模型（读取数据）再渲染：读取失败的业务异常（404/503）不能被渲染器包装成 500
            page = pages.search((SiteRoute.VodSearch) route);
            status = HttpStatus.OK;
        } catch (ApiException e) {
            if (e.code() != ErrorCode.INVALID_PARAMETER) {
                throw e;
            }
            page = pages.invalid();
            status = HttpStatus.BAD_REQUEST;
        }
        SearchPage view = page;
        return renderer.noStore(status, tpl -> tpl.sitePagesSearch(view));
    }
}

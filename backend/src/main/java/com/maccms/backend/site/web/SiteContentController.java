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

    public SiteContentController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                 LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                 ContentReadService content) {
        this.renderer = renderer;
        this.parser = parser;
        this.pages = new ContentPages(content, sitePages, urls, images, richText);
    }

    @GetMapping({"/", "/index.php", "/index.php/",
            "/index.php/vod/type/**", "/index.php/vod/detail/**", "/vod/type/**", "/vod/detail/**"})
    public ResponseEntity<String> page(HttpServletRequest request) {
        // getRequestURI 是未解码的原始路径；应用挂在根路径（base-url 子目录由反向代理映射，05 文档第 2 节）
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
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
        };
    }
}

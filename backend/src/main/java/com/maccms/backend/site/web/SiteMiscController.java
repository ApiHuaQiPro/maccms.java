package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.SiteMiscSettings;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * B06 杂项页（docs/architecture/16-b06-misc-pages-provide.md 第 4.1–4.5 节）：地图 {@code map/index}、帮助 {@code help/index}、
 * 版权提示 {@code vod/copyright}、站点级 RSS {@code rss/index} 与搜索引擎地图 {@code rss/baidu|google|so|sogou|bing|sm}。
 * GET/HEAD。URL 形态与 B05 相同由 {@link LegacyUrlParser} 判定（站点级 RSS 的规范地址以 .xml 结尾，map/help/rss 只写控制器名的
 * 形态只作输入）；不认查询串与路径参数（404）。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteMiscController {

    static final MediaType RSS = new MediaType("application", "rss+xml", StandardCharsets.UTF_8);
    static final MediaType XML = new MediaType("application", "xml", StandardCharsets.UTF_8);

    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final MiscPages pages;

    public SiteMiscController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls, LegacyUrlParser parser,
                              ContentReadService content, ArticleReadService articles, ActorReadService actors,
                              WebsiteReadService websites, LegacyRowReadService rows, SiteMiscSettings misc) {
        this.renderer = renderer;
        this.parser = parser;
        this.pages = new MiscPages(sitePages, urls, content, articles, actors, websites, rows, misc);
    }

    @GetMapping({"/index.php/map", "/index.php/map.html", "/index.php/map/**", "/map", "/map.html", "/map/**",
            "/index.php/help", "/index.php/help.html", "/index.php/help/**", "/help", "/help.html", "/help/**",
            "/index.php/rss", "/index.php/rss.html", "/index.php/rss.xml", "/index.php/rss/**", "/rss", "/rss.html",
            "/rss.xml", "/rss/**", "/index.php/vod/copyright/**", "/vod/copyright/**"})
    public ResponseEntity<String> page(HttpServletRequest request) {
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        // 兼容模式 ?s= 由 SiteContentController 在入口路径上识别后转交，这里按同一请求重新解析
        SiteRoute route = parser.parse(rawPath, request.getQueryString())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"))
                .route();
        if (!(route instanceof SiteRoute.Legacy l) || !l.page().misc()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        return switch (l.page()) {
            case MAP_INDEX -> {
                var v = pages.map();
                yield renderer.ok(tpl -> tpl.sitePagesMap(v), false);
            }
            case HELP_INDEX -> {
                var v = pages.help();
                yield renderer.ok(tpl -> tpl.sitePagesHelp(v), false);
            }
            case VOD_COPYRIGHT -> {
                var v = pages.copyright(l.id());
                yield renderer.ok(tpl -> tpl.sitePagesCopyright(v), true);
            }
            case RSS_INDEX -> xml(RSS, pages.rss());
            case RSS_BAIDU, RSS_GOOGLE, RSS_SO, RSS_SOGOU, RSS_BING, RSS_SM -> xml(XML, pages.sitemap());
            default -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        };
    }

    private static ResponseEntity<String> xml(MediaType type, String body) {
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}

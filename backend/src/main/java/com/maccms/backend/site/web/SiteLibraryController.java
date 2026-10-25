package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.PasswordTarget;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.PasswordAttempts;
import com.maccms.backend.site.application.PasswordTickets;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.RssDocument;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteFeatures;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.site.web.view.AccessNoticePage;
import com.maccms.backend.site.web.view.LibraryDetailPage;
import com.maccms.backend.site.web.view.LibraryListPage;
import com.maccms.backend.site.web.view.PasswordPage;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 文章、专题、演员、角色、网址、剧情各页，单条 RSS 与 ajax_detail 片段（B05，docs/architecture/15-b05-readonly-pages.md 第 5、7 节）。
 * 只认旧默认 URL 形态（{@code /index.php/<模块>/<操作>/id/<n>[/page/<n>].html} 与 rewrite、suffix_hide 变体，由
 * {@link LegacyUrlParser} 判定，数字 id、不认查询串、非规范形态只输出 canonical）。GET/HEAD；文章详情另接受 POST 校验访问密码。
 *
 * <p>ajax_detail 只在 {@code app.ajax_page} 打开时提供（{@link SiteFeatures}，默认关闭，关闭时 404），输出与详情页相同视图的 HTML 片段
 * （没有外壳）。RSS 为 {@code application/rss+xml}。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteLibraryController {

    private static final Logger log = LoggerFactory.getLogger(SiteLibraryController.class);
    static final MediaType RSS = new MediaType("application", "rss+xml", StandardCharsets.UTF_8);
    private static final String INVALID_PASSWORD = "密码不正确，请重新输入。";

    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final LegacyUrlBuilder urls;
    private final SiteSettings site;
    private final SiteFeatures features;
    private final LibraryPages library;
    private final ContentPages videos;
    private final ContentReadService content;
    private final ArticleReadService articles;
    private final TopicReadService topics;
    private final ActorReadService actors;
    private final RoleReadService roles;
    private final WebsiteReadService websites;
    private final PasswordTickets tickets;
    private final PasswordAttempts attempts;
    private final SitePages sitePages;

    public SiteLibraryController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                 LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                 ContentReadService content, ArticleReadService articles, TopicReadService topics,
                                 ActorReadService actors, RoleReadService roles, WebsiteReadService websites,
                                 VideoPlotReadService plots, SiteFeatures features, PasswordTickets tickets,
                                 PasswordAttempts attempts) {
        this.renderer = renderer;
        this.parser = parser;
        this.urls = urls;
        this.site = sitePages.site();
        this.sitePages = sitePages;
        this.features = features;
        this.content = content;
        this.articles = articles;
        this.topics = topics;
        this.actors = actors;
        this.roles = roles;
        this.websites = websites;
        this.tickets = tickets;
        this.attempts = attempts;
        this.library = new LibraryPages(articles, topics, actors, roles, websites, plots, content, sitePages, urls,
                images, richText, tickets.enabled());
        this.videos = new ContentPages(content, sitePages, urls, images, richText);
    }

    @GetMapping({"/index.php/art/**", "/art/**", "/index.php/topic/**", "/topic/**", "/index.php/actor/**", "/actor/**",
            "/index.php/role/**", "/role/**", "/index.php/website/**", "/website/**", "/index.php/plot/**", "/plot/**",
            "/index.php/vod/plot/**", "/vod/plot/**", "/index.php/vod/role/**", "/vod/role/**",
            "/index.php/vod/rss/**", "/vod/rss/**", "/index.php/vod/ajax_detail/**", "/vod/ajax_detail/**"})
    public ResponseEntity<String> page(HttpServletRequest request) {
        SiteRoute.Legacy route = route(request, request.getQueryString());
        return render(route, articleProof(request, route), "");
    }

    /** 文章访问密码（旧 ajax/pwd 的 2-1；只认 POST）。 */
    @PostMapping({"/index.php/art/detail/**", "/art/detail/**"})
    public ResponseEntity<String> password(HttpServletRequest request,
                                           @RequestParam(name = "pwd", required = false) String pwd) {
        SiteRoute.Legacy route = route(request, null);
        if (route.page() != LegacyPage.ART_DETAIL) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        if (!tickets.enabled()) {
            return render(route, null, "");
        }
        if (!attempts.tryAcquire(PasswordTarget.ARTICLE, route.id())) {
            log.warn("site password attempts limited: target={}", PasswordTarget.ARTICLE.code());
            return renderer.noStore(HttpStatus.TOO_MANY_REQUESTS, tpl -> tpl.sitePagesAccessNotice(new AccessNoticePage(
                    sitePages.content("请稍后再试 - " + site.name(), "", "", site.canonical(urls.legacy(route)), List.of()),
                    "请稍后再试", "尝试次数过多，请稍后再试。", Optional.empty())));
        }
        if (!articles.verifyPassword(route.id(), pwd)) {
            return render(route, null, INVALID_PASSWORD);
        }
        ResponseCookie cookie = ResponseCookie.from(PasswordTickets.cookieName(PasswordTarget.ARTICLE, route.id()),
                        tickets.issue(PasswordTarget.ARTICLE, route.id(), pwd))
                .httpOnly(true).sameSite("Lax").secure(site.baseUrl().href().startsWith("https:"))
                .path(site.homePath().href()).maxAge(tickets.maxAgeSeconds()).build();
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, site.publicPath(urls.legacy(route)).href())
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex")
                .build();
    }

    private ResponseEntity<String> render(SiteRoute.Legacy route, PasswordProof proof, String error) {
        LegacyPage page = route.page();
        if (page.rss()) {
            return rss(route);
        }
        boolean ajax = page.target().contains("/ajax_");
        if (ajax && !features.ajaxPage()) {
            // 旧版 app.ajax_page=0 时报"ajax页功能关闭中"（Base.php:45-51）；新版不提供该入口
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        if (page == LegacyPage.VOD_AJAX_DETAIL) {
            var v = videos.detail(new SiteRoute.VodDetail(route.id()));
            return renderer.ok(tpl -> tpl.siteAjaxVideoDetail(v), true);
        }
        LibraryPages.Result r = switch (page) {
            case ART_INDEX, ART_TYPE, ACTOR_INDEX, ACTOR_TYPE, WEBSITE_INDEX, WEBSITE_TYPE, TOPIC_INDEX, ROLE_INDEX,
                 PLOT_INDEX, VOD_ROLE -> library.list(route);
            case ART_DETAIL, TOPIC_DETAIL, ACTOR_DETAIL, ROLE_DETAIL, WEBSITE_DETAIL, PLOT_DETAIL, VOD_PLOT,
                 ART_AJAX_DETAIL, TOPIC_AJAX_DETAIL, ACTOR_AJAX_DETAIL, ROLE_AJAX_DETAIL, WEBSITE_AJAX_DETAIL,
                 PLOT_AJAX_DETAIL -> library.detail(route, proof, error);
            default -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        };
        return switch (r.view()) {
            case LibraryListPage v -> renderer.ok(tpl -> tpl.sitePagesLibrary(v), false);
            case LibraryDetailPage v when ajax -> renderer.ok(tpl -> tpl.siteAjaxDetail(v), true);
            case LibraryDetailPage v -> renderer.ok(tpl -> tpl.sitePagesLibraryDetail(v), false);
            case PasswordPage v when !ajax -> renderer.noStore(r.status(), tpl -> tpl.sitePagesPassword(v));
            case AccessNoticePage v when !ajax -> renderer.noStore(r.status(), tpl -> tpl.sitePagesAccessNotice(v));
            // 片段中不提供密码表单与提示：受限文章的 ajax_detail 与详情页的受限状态相同，只给状态码
            default -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        };
    }

    /** 单条 RSS：与详情页相同的可见性与访问判定；受限内容（密码、积分）不输出（403）。 */
    private ResponseEntity<String> rss(SiteRoute.Legacy route) {
        long id = route.id();
        String title;
        String description;
        long updatedAt;
        SafeUrl link;
        try {
            switch (route.page()) {
                case VOD_RSS, PLOT_RSS -> {
                    var v = content.getVideo(id);
                    title = v.name();
                    description = v.blurb();
                    updatedAt = v.updatedAt();
                    link = site.canonical(route.page() == LegacyPage.VOD_RSS ? urls.vodDetail(id)
                            : urls.legacy(SiteRoute.Legacy.paged(LegacyPage.PLOT_DETAIL, id, 1)));
                }
                case ART_RSS -> {
                    var a = articles.getArticle(id);
                    title = a.name();
                    description = a.blurb();
                    updatedAt = a.updatedAt();
                    link = site.canonical(urls.legacy(SiteRoute.Legacy.paged(LegacyPage.ART_DETAIL, id, 1)));
                }
                case TOPIC_RSS -> {
                    var t = topics.getTopic(id);
                    title = t.name();
                    description = t.blurb();
                    updatedAt = t.updatedAt();
                    link = site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.TOPIC_DETAIL, id)));
                }
                case ACTOR_RSS -> {
                    var a = actors.getActor(id);
                    title = a.name();
                    description = a.blurb();
                    updatedAt = a.updatedAt();
                    link = site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.ACTOR_DETAIL, id)));
                }
                case ROLE_RSS -> {
                    var r = roles.getRole(id);
                    title = r.name();
                    description = r.remarks();
                    updatedAt = r.updatedAt();
                    link = site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.ROLE_DETAIL, id)));
                }
                case WEBSITE_RSS -> {
                    var w = websites.getWebsite(id);
                    title = w.name();
                    description = w.blurb();
                    updatedAt = w.updatedAt();
                    link = site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.WEBSITE_DETAIL, id)));
                }
                default -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
            }
        } catch (ApiException e) {
            if (e.code() == ErrorCode.ARTICLE_PASSWORD_REQUIRED || e.code() == ErrorCode.ARTICLE_POINTS_REQUIRED) {
                return renderer.noStore(HttpStatus.FORBIDDEN, tpl -> tpl.sitePagesAccessNotice(new AccessNoticePage(
                        sitePages.content("内容受限 - " + site.name(), "", "", site.canonical(urls.legacy(route)),
                                List.of()), "内容受限", "该内容需要访问密码或会员积分。", Optional.empty())));
            }
            throw e;
        }
        String body = RssDocument.of(site.name(), site.canonical(urls.home()), site.description(),
                LegacyTextEntities.decodeOnce(title), link, LegacyTextEntities.decodeOnce(description).strip(), updatedAt);
        return ResponseEntity.ok()
                .contentType(RSS)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private SiteRoute.Legacy route(HttpServletRequest request, String query) {
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        SiteRoute route = parser.parse(rawPath, query)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"))
                .route();
        if (!(route instanceof SiteRoute.Legacy l) || l.page().episode()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        return l;
    }

    private PasswordProof articleProof(HttpServletRequest request, SiteRoute.Legacy route) {
        if (route.page() != LegacyPage.ART_DETAIL || request.getCookies() == null || !tickets.enabled()) {
            return null;
        }
        String name = PasswordTickets.cookieName(PasswordTarget.ARTICLE, route.id());
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) {
                Optional<PasswordProof> p = tickets.verify(PasswordTarget.ARTICLE, route.id(), c.getValue());
                if (p.isPresent()) {
                    return p.get();
                }
            }
        }
        return null;
    }
}

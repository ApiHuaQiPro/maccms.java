package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.content.application.view.ActorDetailDto;
import com.maccms.backend.content.application.view.ActorSummaryDto;
import com.maccms.backend.content.application.view.ArticleContentPageDto;
import com.maccms.backend.content.application.view.ArticleDetailDto;
import com.maccms.backend.content.application.view.ArticleSummaryDto;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.PlotDto;
import com.maccms.backend.content.application.view.RoleDetailDto;
import com.maccms.backend.content.application.view.RoleSummaryDto;
import com.maccms.backend.content.application.view.ScreenshotDto;
import com.maccms.backend.content.application.view.TopicDetailDto;
import com.maccms.backend.content.application.view.TopicSummaryDto;
import com.maccms.backend.content.application.view.VideoDetailDto;
import com.maccms.backend.content.application.view.VideoPlotListResponse;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.content.application.view.WebsiteDetailDto;
import com.maccms.backend.content.application.view.WebsiteSummaryDto;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.RichTextSanitizer;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.AccessNoticePage;
import com.maccms.backend.site.web.view.BuyConfirmPage;
import com.maccms.backend.site.web.view.ContentForm;
import com.maccms.backend.site.web.view.ContentCard;
import com.maccms.backend.site.web.view.LibraryDetailPage;
import com.maccms.backend.site.web.view.LibraryListPage;
import com.maccms.backend.site.web.view.MetadataItem;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.PaginationView;
import com.maccms.backend.site.web.view.PasswordPage;
import com.maccms.backend.site.web.view.RichTextBody;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * 文章、专题、演员、角色、网址、剧情各页（B05，docs/architecture/15-b05-readonly-pages.md 第 5 节）的视图模型组装。只调用 content
 * 已发布的只读服务；正文一律经 {@link RichTextCache}（RichTextSanitizer），图片只经 {@link ImageUrlNormalizer}，文本字段按 07 文档
 * 的规则解码一次。列表每页 20 条（旧 listCacheData 的 num 默认值），越界页 404；文章分页与剧情分集按旧版"越界取最后一页"。
 */
public class LibraryPages {

    static final int PAGE_SIZE = VideoListQuery.DEFAULT_PAGE_SIZE;
    /** vod/role 一次列出的角色数（旧 mac_label_vod_role 取 999 条，新版受列表页大小上限 100 约束）。 */
    static final int ROLE_LIMIT = VideoListQuery.MAX_PAGE_SIZE;
    static final String ART_POINTS = "该文章为收费内容，需要会员积分才能阅读。";
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(SITE_ZONE);
    private static final Logger log = LoggerFactory.getLogger(LibraryPages.class);

    private final ArticleReadService articles;
    private final TopicReadService topics;
    private final ActorReadService actors;
    private final RoleReadService roles;
    private final WebsiteReadService websites;
    private final VideoPlotReadService plots;
    private final ContentReadService content;
    private final SitePages pages;
    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final ImageUrlNormalizer images;
    private final RichTextCache richText;
    private final ContentPages contentPages;
    private final boolean passwordsOnline;

    public LibraryPages(ArticleReadService articles, TopicReadService topics, ActorReadService actors,
                        RoleReadService roles, WebsiteReadService websites, VideoPlotReadService plots,
                        ContentReadService content, SitePages pages, LegacyUrlBuilder urls, ImageUrlNormalizer images,
                        RichTextCache richText, boolean passwordsOnline) {
        this.articles = articles;
        this.topics = topics;
        this.actors = actors;
        this.roles = roles;
        this.websites = websites;
        this.plots = plots;
        this.content = content;
        this.pages = pages;
        this.site = pages.site();
        this.urls = urls;
        this.images = images;
        this.richText = richText;
        this.contentPages = new ContentPages(content, pages, urls, images, richText);
        this.passwordsOnline = passwordsOnline;
    }

    /** 页面与状态。 */
    public record Result(HttpStatus status, Object view) {
    }

    // ------------------------------------------------------------------ 列表

    public Result list(SiteRoute.Legacy route) {
        int page = route.pageNo();
        return switch (route.page()) {
            case ART_INDEX -> categoryList(route, "article-page", "art-index", "文章", ContentKind.ARTICLE, null,
                    articles.listCategories().items(), LegacyPage.ART_TYPE, q -> map(articles.listArticles(q),
                            this::card), "暂无文章");
            case ART_TYPE -> categoryList(route, "article-page", "art-type", null, ContentKind.ARTICLE,
                    (int) route.id(), articles.listCategories().items(), LegacyPage.ART_TYPE,
                    q -> map(articles.listArticles(q), this::card), "该分类暂无文章");
            case ACTOR_INDEX -> categoryList(route, "actor-page", "actor-index", "演员", ContentKind.ACTOR, null,
                    actors.listCategories().items(), LegacyPage.ACTOR_TYPE, q -> map(actors.listActors(q), this::card),
                    "暂无演员");
            case ACTOR_TYPE -> categoryList(route, "actor-page", "actor-type", null, ContentKind.ACTOR, (int) route.id(),
                    actors.listCategories().items(), LegacyPage.ACTOR_TYPE, q -> map(actors.listActors(q), this::card),
                    "该分类暂无演员");
            case WEBSITE_INDEX -> categoryList(route, "website-page", "website-index", "网址", ContentKind.WEBSITE, null,
                    websites.listCategories().items(), LegacyPage.WEBSITE_TYPE,
                    q -> map(websites.listWebsites(q), this::card), "暂无网址");
            case WEBSITE_TYPE -> categoryList(route, "website-page", "website-type", null, ContentKind.WEBSITE,
                    (int) route.id(), websites.listCategories().items(), LegacyPage.WEBSITE_TYPE,
                    q -> map(websites.listWebsites(q), this::card), "该分类暂无网址");
            case TOPIC_INDEX -> categoryList(route, "topic-page", "topic-index", "专题", ContentKind.TOPIC, null,
                    List.of(), null, q -> map(topics.listTopics(q), this::card), "暂无专题");
            case ROLE_INDEX -> categoryList(route, "role-page", "role-index", "角色", ContentKind.ROLE, null, List.of(),
                    null, q -> map(roles.listRoles(q), this::card), "暂无角色");
            case PLOT_INDEX -> plotIndex(page);
            case VOD_ROLE -> vodRoles(route.id());
            default -> throw new IllegalStateException("不是列表页");
        };
    }

    private record Listing(List<ContentCard> cards, long total, long totalPages) {
    }

    private <T> Listing map(PageResponse<T> page, java.util.function.Function<T, ContentCard> toCard) {
        return new Listing(page.items().stream().map(toCard).toList(), page.total(), page.totalPages());
    }

    private Result categoryList(SiteRoute.Legacy route, String pageClass, String kind, String fixedHeading,
                                ContentKind contentKind, Integer categoryId, List<CategoryDto> roots,
                                LegacyPage typePage, java.util.function.Function<ContentListQuery, Listing> load,
                                String empty) {
        List<CategoryDto> chain = categoryId == null ? List.of() : ContentPages.chain(roots, categoryId);
        if (categoryId != null && chain.isEmpty()) {
            throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
        }
        int page = route.pageNo();
        ContentListQuery query = ContentListQuery.parse(contentKind, p -> switch (p) {
            case "categoryId" -> categoryId == null ? null : Integer.toString(categoryId);
            case "page" -> Integer.toString(page);
            case "pageSize" -> Integer.toString(PAGE_SIZE);
            default -> null;
        });
        Listing listing = load.apply(query);
        requireInRange(page, listing.total(), listing.totalPages());
        String heading = fixedHeading != null ? fixedHeading : text(chain.getLast().name());
        List<NavLink> crumbs = new ArrayList<>();
        crumbs.add(home());
        if (categoryId == null) {
            crumbs.add(new NavLink(heading, link(route.page(), 0, 1), true));
        } else {
            for (CategoryDto c : chain) {
                crumbs.add(new NavLink(text(c.name()), link(typePage, c.id(), 1), c.id() == categoryId));
            }
        }
        List<NavLink> categories = new ArrayList<>();
        if (typePage != null) {
            for (CategoryDto c : roots) {
                categories.add(new NavLink(text(c.name()), link(typePage, c.id(), 1),
                        !chain.isEmpty() && chain.getFirst().id() == c.id()));
            }
        }
        String title = heading + (page > 1 ? " - 第" + page + "页" : "") + " - " + site.name();
        return new Result(HttpStatus.OK, new LibraryListPage(chrome(title, route), pageClass, kind, heading,
                List.copyOf(crumbs), List.copyOf(categories), "", listing.cards(), empty,
                pagination(page, listing.totalPages(), n -> link(route.page(), route.id(), n))));
    }

    /** 旧 plot/index：有分集剧情的视频（模板标签 plot=1，Vod.php:484-486），按更新时间倒序。 */
    private Result plotIndex(int page) {
        PageResponse<VideoSummaryDto> list = content.listVideos(new VideoListQuery(null, VideoSort.TIME, true, page,
                PAGE_SIZE, VideoFilter.NONE, false, VideoLegacyConditions.builder().plot(1).build()));
        requireInRange(page, list.total(), list.totalPages());
        List<ContentCard> cards = list.items().stream().map(v -> new ContentCard(text(v.name()), text(v.remarks()),
                link(LegacyPage.PLOT_DETAIL, v.id(), 1), images.normalize(v.pic()))).toList();
        String heading = "分集剧情";
        SiteRoute.Legacy route = SiteRoute.Legacy.paged(LegacyPage.PLOT_INDEX, 0, page);
        return new Result(HttpStatus.OK, new LibraryListPage(chrome(heading + (page > 1 ? " - 第" + page + "页" : "")
                + " - " + site.name(), route), "plot-page", "plot-index", heading,
                List.of(home(), new NavLink(heading, link(LegacyPage.PLOT_INDEX, 0, 1), true)), List.of(), "", cards,
                "暂无剧情", pagination(page, list.totalPages(), n -> link(LegacyPage.PLOT_INDEX, 0, n))));
    }

    /** 旧 vod/role：视频（与详情页相同的可见性、密码、版权判定）+ 该视频的角色（Vod.php:80-84、All.php:421-440）。 */
    private Result vodRoles(long vodId) {
        VideoDetailDto v = content.getVideo(vodId);
        PageResponse<RoleSummaryDto> list = roles.listRoles(ContentListQuery.parse(ContentKind.ROLE, p -> switch (p) {
            case "videoId" -> Long.toString(vodId);
            case "pageSize" -> Integer.toString(ROLE_LIMIT);
            default -> null;
        }));
        String name = text(v.name());
        SiteRoute.Legacy route = SiteRoute.Legacy.of(LegacyPage.VOD_ROLE, vodId);
        return new Result(HttpStatus.OK, new LibraryListPage(chrome(name + " 角色 - " + site.name(), route),
                "role-page", "vod-role", name + " 角色",
                List.of(home(), new NavLink(name, site.publicPath(urls.vodDetail(vodId)), false),
                        new NavLink("角色", link(LegacyPage.VOD_ROLE, vodId, 1), true)), List.of(), "",
                list.items().stream().map(this::card).toList(), "该视频暂无角色", Optional.empty()));
    }

    // ------------------------------------------------------------------ 详情

    /**
     * @param proof 文章访问密码凭据（签名票据校验通过后），可为 null
     * @param error 密码页的错误提示，可为空串
     */
    public Result detail(SiteRoute.Legacy route, PasswordProof proof, String error) {
        return detail(route, proof, error, ContentMember.GUEST);
    }

    /** @param member 本请求的会员（B10）：收费文章给已登录会员购买确认表单（19 文档第 5.2 节） */
    Result detail(SiteRoute.Legacy route, PasswordProof proof, String error, ContentMember member) {
        return switch (route.page()) {
            case ART_DETAIL, ART_AJAX_DETAIL -> article(route, proof, error, member);
            case TOPIC_DETAIL, TOPIC_AJAX_DETAIL -> topic(route);
            case ACTOR_DETAIL, ACTOR_AJAX_DETAIL -> actor(route);
            case ROLE_DETAIL, ROLE_AJAX_DETAIL -> role(route);
            case WEBSITE_DETAIL, WEBSITE_AJAX_DETAIL -> website(route);
            case PLOT_DETAIL, VOD_PLOT, PLOT_AJAX_DETAIL -> plot(route);
            default -> throw new IllegalStateException("不是详情页");
        };
    }

    private Result article(SiteRoute.Legacy route, PasswordProof proof, String error, ContentMember member) {
        ArticleDetailDto a;
        // 旧 $param['page']（mac_param_url：intval(page) < 1 时为 1）参与按页收费的购买记录匹配
        int requested = route.page() == LegacyPage.ART_AJAX_DETAIL ? 1 : route.pageNo();
        try {
            a = articles.getArticle(route.id(), requested, proof);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.ARTICLE_POINTS_REQUIRED) {
                return articleConfirm(route, requested, member);
            }
            if (e.code() == ErrorCode.ARTICLE_PASSWORD_REQUIRED) {
                SafeUrl self = link(LegacyPage.ART_DETAIL, route.id(), 1);
                return new Result(HttpStatus.FORBIDDEN, new PasswordPage(chrome("需要访问密码 - " + site.name(),
                        SiteRoute.Legacy.of(LegacyPage.ART_DETAIL, route.id())), "需要访问密码",
                        passwordsOnline ? PlaybackPages.PASSWORD_ONLINE : PlaybackPages.PASSWORD_OFFLINE,
                        passwordsOnline ? Optional.of(self) : Optional.empty(), error, Optional.empty()));
            }
            throw e;
        }
        List<ArticleContentPageDto> parts = a.pages();
        int count = Math.max(1, Math.min(parts.size(), VideoListQuery.MAX_PAGE));
        // 旧 mac_label_art_detail：page < 1 当 1，越界取最后一页（application/common.php:2708）
        int page = route.page() == LegacyPage.ART_AJAX_DETAIL ? 1 : Math.min(route.pageNo(), count);
        ArticleContentPageDto current = parts.isEmpty() ? new ArticleContentPageDto(1, "", "", "") : parts.get(page - 1);
        List<CategoryDto> roots = articles.listCategories().items();
        String name = text(a.name());
        List<NavLink> crumbs = crumbs(roots, a.category().id(), LegacyPage.ART_TYPE, name,
                link(LegacyPage.ART_DETAIL, a.id(), 1));
        List<MetadataItem> meta = new ArrayList<>();
        add(meta, "分类", text(a.category().name()));
        add(meta, "作者", text(a.author()));
        add(meta, "来源", text(a.source()));
        add(meta, "标签", text(a.tag()));
        add(meta, "更新时间", date(a.updatedAt()));
        Optional<PaginationView> pager = pagination(page, count, n -> link(LegacyPage.ART_DETAIL, a.id(), n));
        SiteRoute.Legacy canonical = SiteRoute.Legacy.paged(LegacyPage.ART_DETAIL, a.id(), page);
        return new Result(HttpStatus.OK, new LibraryDetailPage(
                pages.content(name + (page > 1 ? " - 第" + page + "页" : "") + " - " + site.name(),
                        ContentPages.truncate(text(a.blurb()).strip()), text(a.tag()),
                        site.canonical(urls.legacy(canonical)), List.of()),
                "article-page", "art-detail", name, text(a.subTitle()), crumbs, images.normalize(a.pic()),
                List.copyOf(meta), Optional.empty(), text(current.title()), text(current.note()),
                body("art", a.id(), page, a.updatedAt(), current.content()), text(a.blurb()).strip(), pager, List.of(),
                screenshots(a.screenshots()), List.of(), List.of(), ""));
    }

    /**
     * 收费文章的购买确认（B10，旧 3003：Base.php:131-140，19 文档第 5.2 节）：已登录会员带 {@code mid=2, type=1, sid=页码} 的购买表单，
     * 游客带登录链接。
     */
    private Result articleConfirm(SiteRoute.Legacy route, int page, ContentMember member) {
        int points = articles.requiredPoints(route.id()).orElse(0);
        Optional<ContentForm> form = member.loggedIn()
                ? Optional.of(ContentForms.buy(site, urls, 2, route.id(), page, 0, 1)) : Optional.empty();
        Optional<SafeUrl> login = member.loggedIn() ? Optional.empty() : Optional.of(site.publicPath(urls.user("login")));
        return new Result(HttpStatus.FORBIDDEN, new BuyConfirmPage(chrome("需要积分 - " + site.name(),
                SiteRoute.Legacy.of(LegacyPage.ART_DETAIL, route.id())), "需要积分",
                "观看此数据，需要支付【" + points + "】积分，确认支付吗？", form, login, Optional.empty()));
    }

    private Result topic(SiteRoute.Legacy route) {
        TopicDetailDto t = topics.getTopic(route.id());
        String name = text(t.name());
        List<NavLink> crumbs = List.of(home(), new NavLink("专题", link(LegacyPage.TOPIC_INDEX, 0, 1), false),
                new NavLink(name, link(LegacyPage.TOPIC_DETAIL, t.id(), 1), true));
        List<MetadataItem> meta = new ArrayList<>();
        add(meta, "标签", text(t.tag()));
        add(meta, "更新时间", date(t.updatedAt()));
        String description = text(t.description()).isEmpty() ? text(t.blurb()) : text(t.description());
        return new Result(HttpStatus.OK, new LibraryDetailPage(
                pages.content((text(t.title()).isEmpty() ? name : text(t.title())) + " - " + site.name(),
                        ContentPages.truncate(description.strip()), text(t.keywords()),
                        site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.TOPIC_DETAIL, t.id()))), List.of()),
                "topic-page", "topic-detail", name, text(t.subTitle()), crumbs, images.normalize(t.pic()),
                List.copyOf(meta), Optional.empty(), "", "", body("topic", t.id(), 0, t.updatedAt(), t.content()),
                text(t.blurb()).strip(), Optional.empty(), List.of(), List.of(), contentPages.cards(t.relatedVideos()),
                t.relatedArticles().stream().map(this::card).toList(), "相关文章"));
    }

    private Result actor(SiteRoute.Legacy route) {
        ActorDetailDto a = actors.getActor(route.id());
        List<CategoryDto> roots = actors.listCategories().items();
        String name = text(a.name());
        List<MetadataItem> meta = new ArrayList<>();
        add(meta, "别名", text(a.alias()));
        add(meta, "性别", text(a.sex()));
        add(meta, "地区", text(a.area()));
        add(meta, "身高", text(a.height()));
        add(meta, "体重", text(a.weight()));
        add(meta, "生日", text(a.birthday()));
        add(meta, "出生地", text(a.birthArea()));
        add(meta, "血型", text(a.blood()));
        add(meta, "星座", text(a.starSign()));
        add(meta, "毕业院校", text(a.school()));
        add(meta, "代表作品", text(a.works()));
        add(meta, "标签", text(a.tag()));
        return new Result(HttpStatus.OK, new LibraryDetailPage(
                pages.content(name + " - " + site.name(), ContentPages.truncate(text(a.blurb()).strip()), text(a.tag()),
                        site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.ACTOR_DETAIL, a.id()))), List.of()),
                "actor-page", "actor-detail", name, text(a.enName()),
                crumbs(roots, a.category().id(), LegacyPage.ACTOR_TYPE, name, link(LegacyPage.ACTOR_DETAIL, a.id(), 1)),
                images.normalize(a.pic()), List.copyOf(meta), Optional.empty(), "", "",
                body("actor", a.id(), 0, a.updatedAt(), a.content()), text(a.blurb()).strip(), Optional.empty(),
                List.of(), List.of(), List.of(), List.of(), ""));
    }

    private Result role(SiteRoute.Legacy route) {
        RoleDetailDto r = roles.getRole(route.id());
        String name = text(r.name());
        List<MetadataItem> meta = new ArrayList<>();
        add(meta, "演员", text(r.actor()));
        add(meta, "所属视频", text(r.video().name()));
        add(meta, "备注", text(r.remarks()));
        List<ContentCard> video = r.video().id() > 0 ? List.of(new ContentCard(text(r.video().name()), "",
                site.publicPath(urls.vodDetail(r.video().id())), Optional.empty())) : List.of();
        return new Result(HttpStatus.OK, new LibraryDetailPage(
                pages.content(name + " - " + site.name(), "", "",
                        site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.ROLE_DETAIL, r.id()))), List.of()),
                "role-page", "role-detail", name, "",
                List.of(home(), new NavLink("角色", link(LegacyPage.ROLE_INDEX, 0, 1), false),
                        new NavLink(name, link(LegacyPage.ROLE_DETAIL, r.id(), 1), true)),
                images.normalize(r.pic()), List.copyOf(meta), Optional.empty(), "", "",
                body("role", r.id(), 0, r.updatedAt(), r.content()), "", Optional.empty(), List.of(), List.of(),
                List.of(), video, "所属视频"));
    }

    private Result website(SiteRoute.Legacy route) {
        WebsiteDetailDto w = websites.getWebsite(route.id());
        List<CategoryDto> roots = websites.listCategories().items();
        String name = text(w.name());
        List<MetadataItem> meta = new ArrayList<>();
        add(meta, "地区", text(w.area()));
        add(meta, "语言", text(w.lang()));
        add(meta, "标签", text(w.tag()));
        add(meta, "更新时间", date(w.updatedAt()));
        // 网址本身是用户点击才访问的导航链接（不是页面加载的外部资源）；只输出 http/https 地址
        Optional<SafeUrl> target = SafeUrl.parse(w.url()).filter(SafeUrl::isAbsolute);
        Optional<SafeUrl> logo = images.normalize(w.logo()).or(() -> images.normalize(w.pic()));
        return new Result(HttpStatus.OK, new LibraryDetailPage(
                pages.content(name + " - " + site.name(), ContentPages.truncate(text(w.blurb()).strip()), text(w.tag()),
                        site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.WEBSITE_DETAIL, w.id()))), List.of()),
                "website-page", "website-detail", name, text(w.subTitle()),
                crumbs(roots, w.category().id(), LegacyPage.WEBSITE_TYPE, name, link(LegacyPage.WEBSITE_DETAIL, w.id(), 1)),
                logo, List.copyOf(meta), target, "", "", body("website", w.id(), 0, w.updatedAt(), w.content()),
                text(w.blurb()).strip(), Optional.empty(), List.of(), screenshots(w.screenshots()), List.of(), List.of(),
                ""));
    }

    /** 旧 plot/detail、vod/plot：视频的分集剧情，page 为第几集剧情（越界取最后一集，与文章分页相同的规则）。 */
    private Result plot(SiteRoute.Legacy route) {
        VideoDetailDto v = content.getVideo(route.id());
        VideoPlotListResponse list = plots.getPlots(route.id());
        List<PlotDto> items = list.items();
        int count = Math.max(1, Math.min(items.size(), VideoListQuery.MAX_PAGE));
        int page = route.page() == LegacyPage.PLOT_AJAX_DETAIL ? 1 : Math.min(route.pageNo(), count);
        LegacyPage self = route.page() == LegacyPage.VOD_PLOT ? LegacyPage.VOD_PLOT : LegacyPage.PLOT_DETAIL;
        PlotDto current = items.isEmpty() ? new PlotDto(1, "", "") : items.get(page - 1);
        String name = text(v.name());
        List<NavLink> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(items.size(), count); i++) {
            String label = text(items.get(i).name());
            parts.add(new NavLink(label.isEmpty() ? "第" + (i + 1) + "集" : label, link(self, v.id(), i + 1),
                    i + 1 == page));
        }
        return new Result(HttpStatus.OK, new LibraryDetailPage(
                pages.content(name + " 分集剧情" + (page > 1 ? " - 第" + page + "集" : "") + " - " + site.name(), "", "",
                        site.canonical(urls.legacy(SiteRoute.Legacy.paged(self, v.id(), page))), List.of()),
                "plot-page", "plot-detail", name + " 分集剧情", "",
                List.of(home(), new NavLink(name, site.publicPath(urls.vodDetail(v.id())), false),
                        new NavLink("分集剧情", link(self, v.id(), 1), true)),
                images.normalize(v.pic()), List.of(), Optional.empty(), text(current.name()), "",
                body("plot", v.id(), page, v.updatedAt(), current.detail()), "",
                pagination(page, count, n -> link(self, v.id(), n)), List.copyOf(parts), List.of(), List.of(), List.of(),
                ""));
    }

    // ------------------------------------------------------------------ 卡片与共用

    ContentCard card(ArticleSummaryDto a) {
        return new ContentCard(text(a.name()), text(a.remarks()).isEmpty() ? text(a.category().name()) : text(a.remarks()),
                link(LegacyPage.ART_DETAIL, a.id(), 1), images.normalize(a.pic()));
    }

    ContentCard card(TopicSummaryDto t) {
        return new ContentCard(text(t.name()), text(t.remarks()), link(LegacyPage.TOPIC_DETAIL, t.id(), 1),
                images.normalize(t.pic()));
    }

    ContentCard card(ActorSummaryDto a) {
        return new ContentCard(text(a.name()), text(a.remarks()).isEmpty() ? text(a.area()) : text(a.remarks()),
                link(LegacyPage.ACTOR_DETAIL, a.id(), 1), images.normalize(a.pic()));
    }

    ContentCard card(RoleSummaryDto r) {
        return new ContentCard(text(r.name()), text(r.actor()), link(LegacyPage.ROLE_DETAIL, r.id(), 1),
                images.normalize(r.pic()));
    }

    ContentCard card(WebsiteSummaryDto w) {
        return new ContentCard(text(w.name()), text(w.remarks()), link(LegacyPage.WEBSITE_DETAIL, w.id(), 1),
                images.normalize(w.logo()).or(() -> images.normalize(w.pic())));
    }

    private List<ContentCard> screenshots(List<ScreenshotDto> shots) {
        return shots.stream().map(s -> new ContentCard(text(s.name()), "", site.homePath(), images.normalize(s.url())))
                .toList();
    }

    private RichTextBody body(String kind, long id, int part, long updatedAt, String raw) {
        if (raw == null || raw.isBlank()) {
            return RichTextBody.EMPTY;
        }
        RichTextSanitizer.Result r = richText.sanitize(kind, id, part, updatedAt, raw);
        if (r.outcome() == RichTextSanitizer.Outcome.TOO_LONG || r.outcome() == RichTextSanitizer.Outcome.UNSTABLE) {
            log.warn("site content not rendered: outcome={} kind={} id={}", r.outcome(), kind, id);
        }
        return new RichTextBody(r.html());
    }

    private List<NavLink> crumbs(List<CategoryDto> roots, int categoryId, LegacyPage typePage, String name,
                                 SafeUrl self) {
        List<NavLink> crumbs = new ArrayList<>();
        crumbs.add(home());
        for (CategoryDto c : ContentPages.chain(roots, categoryId)) {
            crumbs.add(new NavLink(text(c.name()), link(typePage, c.id(), 1), false));
        }
        crumbs.add(new NavLink(name, self, true));
        return List.copyOf(crumbs);
    }

    private static void requireInRange(int page, long total, long totalPages) {
        boolean out = total == 0 ? page > 1 : page > totalPages;
        if (out) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
    }

    private static Optional<PaginationView> pagination(int page, long totalPages, IntFunction<SafeUrl> link) {
        int count = (int) Math.min(totalPages, VideoListQuery.MAX_PAGE);
        if (count <= 1) {
            return Optional.empty();
        }
        return Optional.of(new PaginationView(page, count, page > 1 ? Optional.of(link.apply(page - 1)) : Optional.empty(),
                page < count ? Optional.of(link.apply(page + 1)) : Optional.empty()));
    }

    private com.maccms.backend.site.web.view.PageChrome chrome(String title, SiteRoute.Legacy canonical) {
        return pages.content(title, "", "", site.canonical(urls.legacy(canonical)), List.of());
    }

    private NavLink home() {
        return new NavLink("首页", site.homePath(), false);
    }

    /** B05 页面（id 为 0 表示没有 id；页码 1 不输出）。 */
    SafeUrl link(LegacyPage page, long id, int pageNo) {
        SiteRoute.Legacy route = page.paged() ? SiteRoute.Legacy.paged(page, id, pageNo) : SiteRoute.Legacy.of(page, id);
        return site.publicPath(urls.legacy(route));
    }

    private static void add(List<MetadataItem> items, String label, String value) {
        String v = value == null ? "" : value.strip();
        if (!v.isEmpty()) {
            items.add(new MetadataItem(label, v));
        }
    }

    private static String date(long epochSecond) {
        return epochSecond > 0 ? DATE.format(Instant.ofEpochSecond(epochSecond)) : "";
    }

    private static String text(String raw) {
        return LegacyTextEntities.decodeOnce(raw);
    }

    /** 测试用：各页面的 class 约定（MACC-54）。 */
    static final Map<LegacyPage, String> PAGE_CLASSES = Map.of(LegacyPage.ART_DETAIL, "article-page",
            LegacyPage.TOPIC_DETAIL, "topic-page", LegacyPage.ACTOR_DETAIL, "actor-page", LegacyPage.ROLE_DETAIL,
            "role-page", LegacyPage.WEBSITE_DETAIL, "website-page", LegacyPage.PLOT_DETAIL, "plot-page");
}

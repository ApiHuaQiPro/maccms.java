package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.VideoDetailDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.RichTextSanitizer;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.CategoryPage;
import com.maccms.backend.site.web.view.DetailPage;
import com.maccms.backend.site.web.view.HomePage;
import com.maccms.backend.site.web.view.MetadataItem;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.PaginationView;
import com.maccms.backend.site.web.view.RichTextBody;
import com.maccms.backend.site.web.view.VideoCardView;
import com.maccms.backend.site.web.view.VideoSection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三页（首页、分类列表、视频公开详情）的视图模型组装。只调用 content 已发布的只读服务
 * {@link ContentReadService} 与 {@code content.application.view} 类型；不访问 Mapper、数据库或旧 PHP。
 *
 * <p>规则（03 方案第 3 节，与旧版的差异见 docs/architecture/08-site-pages.md）：
 * <ul>
 * <li>文本字段按 07 文档第 4.3 节的字段清单经 {@link LegacyTextEntities#decodeOnce} 解码一次，模板再按上下文转义；</li>
 * <li>图片只经 {@link ImageUrlNormalizer}，正文只经 {@link RichTextSanitizer}（经 {@link RichTextCache}）；</li>
 * <li>URL 只由 {@link LegacyUrlBuilder} 生成，canonical 只由配置的 base-url 生成；</li>
 * <li>任一查询失败时异常原样抛出，由 SiteExceptionHandler 输出完整的错误页（不输出半页）。</li>
 * </ul>
 */
public class ContentPages {

    /** 分类页每页条数，与旧 app.page_size 默认值、契约 pageSize 默认值一致（application/extra/maccms.php:92）。 */
    static final int CATEGORY_PAGE_SIZE = VideoListQuery.DEFAULT_PAGE_SIZE;
    /** 首页"最近更新"条数。 */
    static final int HOME_LATEST_SIZE = 12;
    /** 首页按顶级分类展示的区块数上限（03 方案 3.2：每个区块一次查询，须设上限）。 */
    static final int HOME_CATEGORY_BLOCKS = 4;
    /** 首页每个分类区块的条数。 */
    static final int HOME_CATEGORY_SIZE = 6;
    /** 详情页"同类推荐"条数（不含本片）。 */
    static final int RELATED_SIZE = 6;
    /** meta description 取简介的最大字符数。 */
    static final int DESCRIPTION_MAX = 150;
    /** 时间显示时区。站点时区配置尚无（05 文档第 2 节），按 03 方案 3.5 的默认值，线上取值待核实。 */
    static final ZoneId SITE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(SITE_ZONE);

    private static final Logger log = LoggerFactory.getLogger(ContentPages.class);

    private final ContentReadService content;
    private final SitePages pages;
    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final ImageUrlNormalizer images;
    private final RichTextCache richText;

    public ContentPages(ContentReadService content, SitePages pages, LegacyUrlBuilder urls, ImageUrlNormalizer images,
                        RichTextCache richText) {
        this.content = content;
        this.pages = pages;
        this.site = pages.site();
        this.urls = urls;
        this.images = images;
        this.richText = richText;
    }

    /** 首页：分类网格 + 最近更新 + 前几个顶级分类的最新视频。没有视频的区块不显示。 */
    public HomePage home() {
        List<CategoryDto> roots = content.listCategories().items();
        List<VideoSection> sections = new ArrayList<>();
        List<VideoCardView> latest = cards(content.listVideos(
                new VideoListQuery(null, VideoSort.TIME, true, 1, HOME_LATEST_SIZE)).items());
        if (!latest.isEmpty()) {
            sections.add(new VideoSection("最近更新", Optional.empty(), latest));
        }
        for (CategoryDto c : roots.subList(0, Math.min(HOME_CATEGORY_BLOCKS, roots.size()))) {
            List<VideoCardView> videos;
            try {
                videos = cards(content.listVideos(
                        new VideoListQuery(c.id(), VideoSort.TIME, true, 1, HOME_CATEGORY_SIZE)).items());
            } catch (ApiException e) {
                if (e.code() != ErrorCode.CATEGORY_NOT_FOUND) {
                    throw e;
                }
                // 两次查询之间分类被停用：只跳过该区块，首页不因此变成 404
                continue;
            }
            if (!videos.isEmpty()) {
                sections.add(new VideoSection(text(c.name()), Optional.of(categoryUrl(c.id(), 1)), videos));
            }
        }
        List<NavLink> nav = nav(roots, 0);
        return new HomePage(pages.content(site.name(), "", "", site.canonical(urls.home()), nav), site.name(), nav,
                List.copyOf(sections));
    }

    /**
     * 分类列表页：分类与可见的直接子分类中的视频，按更新时间倒序（旧默认，01 计划 4.2），每页 20 条。
     * 分类不存在/停用/游客无权限 → CATEGORY_NOT_FOUND；越界页（total=0 且 page&gt;1，或 page&gt;totalPages）→ NOT_FOUND。
     */
    public CategoryPage category(SiteRoute.VodType route) {
        List<CategoryDto> roots = content.listCategories().items();
        List<CategoryDto> chain = chain(roots, route.typeId());
        if (chain.isEmpty()) {
            throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
        }
        CategoryDto category = chain.getLast();
        PageResponse<VideoSummaryDto> page = content.listVideos(
                new VideoListQuery(category.id(), VideoSort.TIME, true, route.page(), CATEGORY_PAGE_SIZE));
        boolean outOfRange = page.total() == 0 ? route.page() > 1 : route.page() > page.totalPages();
        if (outOfRange) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }

        String name = text(category.name());
        String title = text(category.title()).isEmpty() ? name : text(category.title());
        if (route.page() > 1) {
            title += " - 第" + route.page() + "页";
        }
        List<NavLink> breadcrumb = new ArrayList<>();
        breadcrumb.add(homeLink());
        for (CategoryDto c : chain) {
            breadcrumb.add(new NavLink(text(c.name()), categoryUrl(c.id(), 1), c == category));
        }
        List<NavLink> children = category.children().stream()
                .map(c -> new NavLink(text(c.name()), categoryUrl(c.id(), 1), false)).toList();

        // 页码上限与公开 API 一致（1000），更大的页无法访问，分页不链接过去
        int pageCount = (int) Math.min(page.totalPages(), VideoListQuery.MAX_PAGE);
        Optional<PaginationView> pagination = Optional.empty();
        if (pageCount > 1) {
            pagination = Optional.of(new PaginationView(route.page(), pageCount,
                    route.page() > 1 ? Optional.of(categoryUrl(category.id(), route.page() - 1)) : Optional.empty(),
                    route.page() < pageCount ? Optional.of(categoryUrl(category.id(), route.page() + 1))
                            : Optional.empty()));
        }
        // canonical 自引用（第 n 页指向第 n 页本身，03 方案 5.2）
        return new CategoryPage(
                pages.content(title + " - " + site.name(), text(category.description()), text(category.keywords()),
                        site.canonical(urls.vodType(category.id(), route.page())), nav(roots, chain.getFirst().id())),
                name, List.copyOf(breadcrumb), children, cards(page.items()), pagination);
    }

    /**
     * 视频公开详情页。不存在/未审核/分类不可见/游客无权限 → VIDEO_NOT_FOUND；观看密码、版权 → 对应 403 码
     * （由 SiteExceptionHandler 按已批准的边界处理）。播放入口不出现（03 方案 P5 未批准）。
     */
    public DetailPage detail(SiteRoute.VodDetail route) {
        VideoDetailDto v = content.getVideo(route.vodId());
        List<CategoryDto> roots = content.listCategories().items();
        List<CategoryDto> chain = chain(roots, v.category().id());
        List<VideoCardView> related = related(v);

        String title = text(v.name());
        List<NavLink> breadcrumb = new ArrayList<>();
        breadcrumb.add(homeLink());
        if (chain.isEmpty()) {
            breadcrumb.add(new NavLink(text(v.category().name()), categoryUrl(v.category().id(), 1), false));
        } else {
            for (CategoryDto c : chain) {
                breadcrumb.add(new NavLink(text(c.name()), categoryUrl(c.id(), 1), false));
            }
        }
        breadcrumb.add(new NavLink(title, site.publicPath(urls.vodDetail(v.id())), true));

        RichTextBody body = RichTextBody.EMPTY;
        if (!v.content().isBlank()) {
            RichTextSanitizer.Result r = richText.sanitize(v.id(), v.updatedAt(), v.content());
            if (r.outcome() == RichTextSanitizer.Outcome.TOO_LONG || r.outcome() == RichTextSanitizer.Outcome.UNSTABLE) {
                // 只记录结果类别与视频 id，不记录正文
                log.warn("site detail content not rendered: outcome={} vodId={}", r.outcome(), v.id());
            }
            body = new RichTextBody(r.html());
        }
        String blurb = text(v.blurb()).strip();

        return new DetailPage(
                pages.content(title + " - " + site.name(), truncate(blurb), text(v.tag()),
                        site.canonical(urls.vodDetail(v.id())),
                        nav(roots, chain.isEmpty() ? 0 : chain.getFirst().id())),
                title, text(v.subTitle()), images.normalize(v.pic()), List.copyOf(breadcrumb), metadata(v, chain),
                body, blurb, related);
    }

    private List<VideoCardView> related(VideoDetailDto v) {
        try {
            return cards(content.listVideos(new VideoListQuery(v.category().id(), VideoSort.TIME, true, 1,
                    RELATED_SIZE + 1)).items().stream().filter(s -> s.id() != v.id()).limit(RELATED_SIZE).toList());
        } catch (ApiException e) {
            if (e.code() != ErrorCode.CATEGORY_NOT_FOUND) {
                throw e;
            }
            return List.of();
        }
    }

    private List<MetadataItem> metadata(VideoDetailDto v, List<CategoryDto> chain) {
        List<MetadataItem> items = new ArrayList<>();
        add(items, "分类", chain.isEmpty() ? text(v.category().name()) : text(chain.getLast().name()));
        add(items, "类型", text(v.classes()));
        add(items, "主演", text(v.actor()));
        add(items, "导演", text(v.director()));
        add(items, "编剧", text(v.writer()));
        add(items, "地区", text(v.area()));
        add(items, "语言", text(v.lang()));
        add(items, "年份", text(v.year()));
        add(items, "上映", v.pubdate());
        add(items, "片长", v.duration());
        add(items, "状态", text(v.remarks()));
        if (v.total() > 0) {
            add(items, "集数", "共 " + v.total() + " 集" + (v.finished() ? "（完结）" : ""));
        }
        add(items, "标签", text(v.tag()));
        if (v.scoreNum() > 0) {
            add(items, "评分", v.score().toPlainString());
        }
        if (v.doubanScore().compareTo(BigDecimal.ZERO) > 0) {
            add(items, "豆瓣评分", v.doubanScore().toPlainString());
        }
        if (v.updatedAt() > 0) {
            add(items, "更新时间", DATE.format(Instant.ofEpochSecond(v.updatedAt())));
        }
        return List.copyOf(items);
    }

    private static void add(List<MetadataItem> items, String label, String value) {
        String v = value == null ? "" : value.strip();
        if (!v.isEmpty()) {
            items.add(new MetadataItem(label, v));
        }
    }

    List<VideoCardView> cards(List<VideoSummaryDto> videos) {
        return videos.stream().map(s -> new VideoCardView(text(s.name()), text(s.remarks()),
                site.publicPath(urls.vodDetail(s.id())), images.normalize(s.pic()))).toList();
    }

    /** 顶级分类导航；currentId 等于某个顶级分类时标记为当前项（0 表示没有）。 */
    List<NavLink> nav(List<CategoryDto> roots, int currentId) {
        return roots.stream().map(c -> new NavLink(text(c.name()), categoryUrl(c.id(), 1), c.id() == currentId))
                .toList();
    }

    private NavLink homeLink() {
        return new NavLink("首页", site.homePath(), false);
    }

    private SafeUrl categoryUrl(int typeId, int page) {
        return site.publicPath(urls.vodType(typeId, page));
    }

    /** 从顶级分类到 id 的路径；不在可见分类树中时为空。 */
    static List<CategoryDto> chain(List<CategoryDto> nodes, int id) {
        for (CategoryDto c : nodes) {
            if (c.id() == id) {
                return List.of(c);
            }
            List<CategoryDto> sub = chain(c.children(), id);
            if (!sub.isEmpty()) {
                List<CategoryDto> path = new ArrayList<>(sub.size() + 1);
                path.add(c);
                path.addAll(sub);
                return List.copyOf(path);
            }
        }
        return List.of();
    }

    /** 旧模板原样输出、由浏览器解码一次的文本字段（07 文档第 4.3 节字段清单）。 */
    private static String text(String raw) {
        return LegacyTextEntities.decodeOnce(raw);
    }

    private static String truncate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").strip();
        if (oneLine.codePointCount(0, oneLine.length()) <= DESCRIPTION_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, DESCRIPTION_MAX)) + "…";
    }
}

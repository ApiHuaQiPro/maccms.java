package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LibraryMatch;
import com.maccms.backend.content.application.LibrarySearchService;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.ModuleFilters;
import com.maccms.backend.site.application.ModuleListPage;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.ContentCard;
import com.maccms.backend.site.web.view.FilterChip;
import com.maccms.backend.site.web.view.FilterGroup;
import com.maccms.backend.site.web.view.HiddenField;
import com.maccms.backend.site.web.view.ModuleListView;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.PaginationView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文章、专题、演员、角色、网址、剧情的筛选页（旧 show）与搜索页（旧 search）的视图模型组装（B05 D-B05-5，15 文档第 7.2 节）。
 * 列表经 {@link LibrarySearchService}（剧情为 {@link ContentReadService#listVideos} 加旧 plot=1 条件）只读查询，可见性、发布状态、
 * 排序白名单与契约 1.2.0 的列表相同；包含匹配条件受搜索开关、并发许可与执行时限约束，开关关闭时在查库前 400。每页 20 条，越界页 404。
 *
 * <ul>
 * <li>搜索页无任何条件：不查库，只显示表单与提示（page&gt;1 → 404），与视频搜索页相同。</li>
 * <li>筛选页：分类须是该模块的可见分类（不存在、不可见 → 404）；无条件时列出该分类（本身 + 直接子分类）的全部内容；演员筛选页未给出
 * 分类时取第一个可见的演员分类（旧 Actor.php:24-35）。</li>
 * <li>筛选组：首字母（A–Z、0-9）与已设置的其他条件（"全部 + 当前值"，便于去掉），最后是排序（时间、人气、评分）。所有链接、表单与
 * canonical 由 {@link LegacyUrlBuilder#moduleListLink} 生成，所以后续筛选与翻页保留分类与其余条件。</li>
 * </ul>
 */
public class ModuleListPages {

    static final int PAGE_SIZE = VideoListQuery.DEFAULT_PAGE_SIZE;
    private static final Map<String, String> LABELS = Map.of("art", "文章", "topic", "专题", "actor", "演员", "role",
            "角色", "website", "网址", "plot", "分集剧情");
    private static final Map<String, String> PAGE_CLASSES = Map.of("art", "article-page", "topic", "topic-page",
            "actor", "actor-page", "role", "role-page", "website", "website-page", "plot", "plot-page");
    private static final Map<String, LegacyPage> INDEX = Map.of("art", LegacyPage.ART_INDEX, "topic",
            LegacyPage.TOPIC_INDEX, "actor", LegacyPage.ACTOR_INDEX, "role", LegacyPage.ROLE_INDEX, "website",
            LegacyPage.WEBSITE_INDEX, "plot", LegacyPage.PLOT_INDEX);
    /** 条件组的显示名（没有候选值的条件只在已设置时出现）。 */
    private static final Map<String, String> KEY_LABELS = Map.ofEntries(Map.entry("ids", "指定内容"),
            Map.entry("tid", "分类"), Map.entry("level", "推荐"), Map.entry("name", "名称"), Map.entry("tag", "标签"),
            Map.entry("class", "类型"), Map.entry("sex", "性别"), Map.entry("area", "地区"), Map.entry("starsign", "星座"),
            Map.entry("blood", "血型"), Map.entry("lang", "语言"), Map.entry("rid", "所属视频"));
    private static final List<String[]> SORTS = List.of(new String[] {"time", "时间"}, new String[] {"hits", "人气"},
            new String[] {"score", "评分"});

    private final LibraryPages library;
    private final LibrarySearchService search;
    private final ArticleReadService articles;
    private final ActorReadService actors;
    private final WebsiteReadService websites;
    private final ContentReadService content;
    private final SitePages pages;
    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final ImageUrlNormalizer images;

    public ModuleListPages(LibraryPages library, LibrarySearchService search, ArticleReadService articles,
                           ActorReadService actors, WebsiteReadService websites, ContentReadService content,
                           SitePages pages, LegacyUrlBuilder urls, ImageUrlNormalizer images) {
        this.library = library;
        this.search = search;
        this.articles = articles;
        this.actors = actors;
        this.websites = websites;
        this.content = content;
        this.pages = pages;
        this.site = pages.site();
        this.urls = urls;
        this.images = images;
    }

    private record Listing(List<ContentCard> cards, long total, long totalPages) {
    }

    public ModuleListView render(SiteRoute.ModuleList route) {
        ModuleListPage page = route.page().page();
        String module = page.module();
        ModuleFilters f = route.filters();
        int pageNo = route.pageNo();
        String label = LABELS.get(module);
        List<CategoryDto> roots = List.of();
        List<CategoryDto> chain = List.of();
        Integer category = route.categoryId();
        if (page.show()) {
            roots = categories(module);
            if (category == null) {
                category = firstCategory(roots).orElseThrow(() ->
                        new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在"));
            }
            chain = ContentPages.chain(roots, category);
            if (chain.isEmpty()) {
                throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
            }
        }
        Integer cat = category;
        String kind = module + "-" + (page.show() ? "show" : "search");
        List<NavLink> crumbs = new ArrayList<>();
        crumbs.add(new NavLink("首页", site.homePath(), false));
        crumbs.add(new NavLink(label, library.link(INDEX.get(module), 0, 1), false));
        for (CategoryDto c : chain) {
            crumbs.add(new NavLink(text(c.name()), link(page, c.id(), ModuleFilters.none(module), 1), c.id() == cat));
        }
        if (!page.show()) {
            crumbs.add(new NavLink("搜索", link(page, null, ModuleFilters.none(module), 1), true));
        }
        List<NavLink> nav = new ArrayList<>();
        for (CategoryDto c : roots) {
            nav.add(new NavLink(text(c.name()), link(page, c.id(), ModuleFilters.none(module), 1),
                    chain.getFirst().id() == c.id()));
        }
        SafeUrl formAction = link(page, cat, ModuleFilters.none(module), 1);
        String formLabel = "搜索" + label;
        SafeUrl canonical = site.canonical(urls.moduleListLink(page, cat, f, pageNo));
        if (!page.show() && f.isEmpty()) {
            if (pageNo > 1) {
                throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
            }
            String heading = "搜索" + label;
            return new ModuleListView(pages.search(heading + " - " + site.name(), Optional.of(canonical), List.of()),
                    PAGE_CLASSES.get(module), kind, heading, List.copyOf(crumbs), List.of(), false, formAction,
                    formLabel, "", List.of(), Optional.empty(), List.of(), List.of(), "", Optional.empty());
        }
        Listing listing = load(module, f, cat, pageNo);
        boolean outOfRange = listing.total() == 0 ? pageNo > 1 : pageNo > listing.totalPages();
        if (outOfRange) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        String base = page.show() ? text(chain.getLast().name()) : label;
        String heading = f.keyword() != null ? (page.show() ? base + "：" : "搜索" + label + "：") + f.keyword()
                : page.show() ? base : label + "筛选结果";
        String title = heading + (pageNo > 1 ? " - 第" + pageNo + "页" : "") + " - " + site.name();
        int pageCount = (int) Math.min(listing.totalPages(), VideoListQuery.MAX_PAGE);
        Optional<PaginationView> pagination = pageCount <= 1 ? Optional.empty()
                : Optional.of(new PaginationView(pageNo, pageCount,
                        pageNo > 1 ? Optional.of(link(page, cat, f, pageNo - 1)) : Optional.empty(),
                        pageNo < pageCount ? Optional.of(link(page, cat, f, pageNo + 1)) : Optional.empty()));
        return new ModuleListView(pages.search(title, Optional.of(canonical), List.of()), PAGE_CLASSES.get(module),
                kind, heading, List.copyOf(crumbs), List.copyOf(nav), false, formAction, formLabel,
                f.keyword() == null ? "" : f.keyword(), hiddenFields(f), Optional.of(listing.total()),
                filterGroups(page, cat, f), listing.cards(), page.show() ? "该分类暂无" + label : "没有找到相关" + label,
                pagination);
    }

    /** 参数不合法（400）：固定提示、空白表单（提交到该模块的搜索页）；不查库、不回显输入、没有 canonical。 */
    public ModuleListView invalid(ModuleListPage requested) {
        ModuleListPage page = requested.page();
        String module = page.module();
        ModuleListPage searchPage = ModuleListPage.byTarget(module + "/search");
        String label = LABELS.get(module);
        return new ModuleListView(pages.search("搜索条件不合法 - " + site.name(), Optional.empty(), List.of()),
                PAGE_CLASSES.get(module), module + "-" + (page.show() ? "show" : "search"), "搜索条件不合法", List.of(),
                List.of(), true, link(searchPage, null, ModuleFilters.none(module), 1), "搜索" + label, "", List.of(),
                Optional.empty(), List.of(), List.of(), "", Optional.empty());
    }

    private Listing load(String module, ModuleFilters f, Integer category, int pageNo) {
        if (module.equals("plot")) {
            VideoFilter vf = f.videoFilter();
            if (vf.usesContainsMatch() && !content.searchEnabled()) {
                throw ApiException.invalidParameter(vf.firstContainsMatchParameter(), "当前未开放关键字搜索");
            }
            VideoListQuery sorting = f.videoSorting();
            // 旧 plot/search 模板的列表：有分集剧情的视频（plot=1，与 plot/index 相同）
            PageResponse<VideoSummaryDto> r = content.listVideos(new VideoListQuery(null, sorting.sort(),
                    sorting.descending(), pageNo, PAGE_SIZE, vf, false, VideoLegacyConditions.builder().plot(1).build()));
            List<ContentCard> cards = r.items().stream().map(v -> new ContentCard(text(v.name()), text(v.remarks()),
                    library.link(LegacyPage.PLOT_DETAIL, v.id(), 1), images.normalize(v.pic()))).toList();
            return new Listing(cards, r.total(), r.totalPages());
        }
        LibraryMatch match = f.match();
        if (match.usesContainsMatch() && !content.searchEnabled()) {
            // 与 content 的判断相同（ContentReadService.guardedRead），这里提前到查库之前
            throw ApiException.invalidParameter(match.firstContainsMatchParameter(), "当前未开放关键字搜索");
        }
        ContentListQuery q = f.query(ModuleListPage.byTarget(module + "/search").kind(), category, pageNo, PAGE_SIZE);
        return switch (module) {
            case "art" -> map(search.articles(q, match), library::card);
            case "topic" -> map(search.topics(q, match), library::card);
            case "actor" -> map(search.actors(q, match), library::card);
            case "role" -> map(search.roles(q, match), library::card);
            case "website" -> map(search.websites(q, match), library::card);
            default -> throw new IllegalStateException("未知模块");
        };
    }

    private static <T> Listing map(PageResponse<T> page, java.util.function.Function<T, ContentCard> toCard) {
        return new Listing(page.items().stream().map(toCard).toList(), page.total(), page.totalPages());
    }

    private List<CategoryDto> categories(String module) {
        return switch (module) {
            case "art" -> articles.listCategories().items();
            case "actor" -> actors.listCategories().items();
            case "website" -> websites.listCategories().items();
            default -> throw new IllegalStateException("该模块没有分类");
        };
    }

    /** 可见分类中 id 最小的一个（旧版 where type_mid、type_status 的 find()，按主键）。 */
    private static Optional<Integer> firstCategory(List<CategoryDto> nodes) {
        Optional<Integer> min = Optional.empty();
        for (CategoryDto c : nodes) {
            Optional<Integer> child = firstCategory(c.children());
            int v = child.map(x -> Math.min(x, c.id())).orElse(c.id());
            min = Optional.of(min.map(m -> Math.min(m, v)).orElse(v));
        }
        return min;
    }

    private SafeUrl link(ModuleListPage page, Integer category, ModuleFilters f, int pageNo) {
        return site.publicPath(urls.moduleListLink(page, category, f, pageNo));
    }

    /** 当前条件中除 wd 外的值（page 不保留：提交关键字回到第 1 页）。 */
    private static List<HiddenField> hiddenFields(ModuleFilters f) {
        List<HiddenField> out = new ArrayList<>();
        for (Map.Entry<String, String> e : f.params().entrySet()) {
            if (!e.getKey().equals("wd")) {
                out.add(new HiddenField(e.getKey(), e.getValue()));
            }
        }
        return List.copyOf(out);
    }

    private List<FilterGroup> filterGroups(ModuleListPage page, Integer category, ModuleFilters f) {
        List<FilterGroup> groups = new ArrayList<>();
        String letter = f.params().get("letter");
        List<FilterChip> letters = new ArrayList<>();
        letters.add(new FilterChip("全部", link(page, category, f.with("letter", null), 1), letter == null));
        for (String v : SearchPages.LETTERS) {
            ModuleFilters next;
            try {
                next = f.with("letter", v);
            } catch (IllegalArgumentException e) {
                continue;
            }
            letters.add(new FilterChip(v, link(page, category, next, 1), v.equals(letter)));
        }
        if (letter != null && !SearchPages.LETTERS.contains(letter)) {
            letters.add(1, new FilterChip(letter, link(page, category, f, 1), true));
        }
        groups.add(new FilterGroup("letter", "首字母", List.copyOf(letters)));
        for (String key : page.sortedKeys()) {
            String v = f.params().get(key);
            if (v == null || key.equals("wd") || key.equals("letter") || key.equals("by") || key.equals("order")) {
                continue;
            }
            groups.add(new FilterGroup(key, KEY_LABELS.getOrDefault(key, key), List.of(
                    new FilterChip("全部", link(page, category, f.with(key, null), 1), false),
                    new FilterChip(v, link(page, category, f, 1), true))));
        }
        String by = f.params().get("by");
        List<FilterChip> sorts = new ArrayList<>();
        for (String[] s : SORTS) {
            boolean current = by == null ? s[0].equals("time") : s[0].equals(by);
            sorts.add(new FilterChip(s[1], link(page, category, f.with("by", s[0].equals("time") ? null : s[0]), 1),
                    current));
        }
        groups.add(new FilterGroup("by", "排序", List.copyOf(sorts)));
        return List.copyOf(groups);
    }

    private static String text(String raw) {
        return LegacyTextEntities.decodeOnce(raw);
    }
}

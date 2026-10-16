package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.CategoryExtendDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SearchFilters;
import com.maccms.backend.site.application.SiteLink;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.FilterChip;
import com.maccms.backend.site.web.view.FilterGroup;
import com.maccms.backend.site.web.view.HiddenField;
import com.maccms.backend.site.web.view.PaginationView;
import com.maccms.backend.site.web.view.SearchPage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * SSR 搜索页的视图模型组装（09 方案第 7.3、7.4 节）。只调用 {@link ContentReadService} 的只读方法与
 * {@code content.application.view} 类型；所有链接由 {@link LegacyUrlBuilder#vodSearchLink} 生成固定路径 + 查询串，
 * 经 {@link SiteSettings#publicPath(SiteLink)} / {@link SiteSettings#canonical(SiteLink)} 成为 {@link SafeUrl}。
 *
 * <ul>
 * <li>无任何条件：不查库，只显示表单与提示；带页码（page&gt;1）→ 404。</li>
 * <li>有条件：一次 {@code listCategories()}（导航与筛选候选值）+ 一次 {@code listVideos}（每页 20 条，默认 time desc）。
 * 带 keyword/classes 而 {@code maccms.content.search.enabled=false} 时在查库前就是 INVALID_PARAMETER（400）。</li>
 * <li>越界页（page&gt;1 且 total=0，或 page&gt;totalPages）→ 404；查询失败的异常原样抛出（503/500 由 SiteExceptionHandler 处理）。</li>
 * </ul>
 */
public class SearchPages {

    /** 每页条数，与分类页、契约 pageSize 默认值一致。 */
    static final int PAGE_SIZE = VideoListQuery.DEFAULT_PAGE_SIZE;
    /** 每组筛选候选值的上限（不含"全部"与不在候选中的当前值）。 */
    static final int MAX_CHOICES = 30;
    /** 排序链接：时间、人气、评分（其余 by 值仍可经 URL 使用）。 */
    static final List<VideoSort> SORT_LINKS = List.of(VideoSort.TIME, VideoSort.HITS, VideoSort.SCORE);

    private final ContentReadService content;
    private final SitePages pages;
    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final ContentPages contentPages;

    public SearchPages(ContentReadService content, SitePages pages, LegacyUrlBuilder urls, ContentPages contentPages) {
        this.content = content;
        this.pages = pages;
        this.site = pages.site();
        this.urls = urls;
        this.contentPages = contentPages;
    }

    public SearchPage search(SiteRoute.VodSearch route) {
        SearchFilters f = route.filters();
        int page = route.page();
        SafeUrl canonical = site.canonical(urls.vodSearchLink(f, page));
        if (f.isEmpty()) {
            if (page > 1) {
                throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
            }
            return new SearchPage(pages.search("搜索视频 - " + site.name(), Optional.of(canonical), List.of()),
                    "搜索视频", false, formAction(), "", List.of(), Optional.empty(), List.of(), List.of(),
                    Optional.empty());
        }
        if (f.usesContainsMatch() && !content.searchEnabled()) {
            // 与 content 的判断相同（ContentReadService.listVideos），这里提前到查库之前
            throw ApiException.invalidParameter(f.keyword() != null ? "keyword" : "classes", "当前未开放关键字搜索");
        }
        List<CategoryDto> roots = content.listCategories().items();
        VideoSort sort = f.sort() == null ? VideoSort.TIME : f.sort();
        boolean descending = f.order() != SearchFilters.SortOrder.ASC;
        PageResponse<VideoSummaryDto> result = content.listVideos(
                new VideoListQuery(null, sort, descending, page, PAGE_SIZE, f.toVideoFilter()));
        boolean outOfRange = result.total() == 0 ? page > 1 : page > result.totalPages();
        if (outOfRange) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }

        String heading = f.keyword() != null ? "搜索：" + f.keyword() : "筛选结果";
        String title = heading + (page > 1 ? " - 第" + page + "页" : "") + " - " + site.name();
        int pageCount = (int) Math.min(result.totalPages(), VideoListQuery.MAX_PAGE);
        Optional<PaginationView> pagination = Optional.empty();
        if (pageCount > 1) {
            pagination = Optional.of(new PaginationView(page, pageCount,
                    page > 1 ? Optional.of(link(f, page - 1)) : Optional.empty(),
                    page < pageCount ? Optional.of(link(f, page + 1)) : Optional.empty()));
        }
        return new SearchPage(pages.search(title, Optional.of(canonical), contentPages.nav(roots, 0)), heading, false,
                formAction(), f.keyword() == null ? "" : f.keyword(), hiddenFields(f), Optional.of(result.total()),
                filterGroups(f, roots), contentPages.cards(result.items()), pagination);
    }

    /** 参数不合法（400）：固定提示、空白表单；不查库、不回显输入、没有 canonical。 */
    public SearchPage invalid() {
        return new SearchPage(pages.search("搜索条件不合法 - " + site.name(), Optional.empty(), List.of()),
                "搜索条件不合法", true, formAction(), "", List.of(), Optional.empty(), List.of(), List.of(),
                Optional.empty());
    }

    private SafeUrl formAction() {
        return site.publicPath(urls.vodSearchLink(SearchFilters.NONE, 1));
    }

    private SafeUrl link(SearchFilters f, int page) {
        return site.publicPath(urls.vodSearchLink(f, page));
    }

    /** 当前条件中除 wd 外的值（page 不保留：提交关键字回到第 1 页，09 方案第 7.2.5 节）。 */
    private static List<HiddenField> hiddenFields(SearchFilters f) {
        List<HiddenField> out = new ArrayList<>();
        for (Map.Entry<String, String> e : f.params().entrySet()) {
            if (!e.getKey().equals("wd")) {
                out.add(new HiddenField(e.getKey(), e.getValue()));
            }
        }
        return List.copyOf(out);
    }

    private List<FilterGroup> filterGroups(SearchFilters f, List<CategoryDto> roots) {
        List<CategoryExtendDto> extend = new ArrayList<>();
        collectExtend(roots, extend);
        List<FilterGroup> groups = new ArrayList<>();
        addGroup(groups, f, "area", "地区", f.area(), extend, CategoryExtendDto::area);
        addGroup(groups, f, "lang", "语言", f.lang(), extend, CategoryExtendDto::lang);
        addGroup(groups, f, "year", "年份", f.year(), extend, CategoryExtendDto::year);
        if (content.searchEnabled()) {
            // 扩展分类是包含匹配，开关关闭时点了只会得到 400，不提供链接
            addGroup(groups, f, "class", "类型", f.classes(), extend, CategoryExtendDto::classes);
        }
        List<FilterChip> sorts = new ArrayList<>();
        for (VideoSort s : SORT_LINKS) {
            boolean current = f.sort() == null ? s == VideoSort.TIME : f.sort() == s;
            sorts.add(new FilterChip(sortLabel(s), link(f.withSort(s), 1), current));
        }
        groups.add(new FilterGroup("by", "排序", List.copyOf(sorts)));
        return List.copyOf(groups);
    }

    /**
     * 一组候选值：所有可见分类 extend 中该键的值的并集（逗号分隔，去重、保持出现顺序），跳过不能作为条件回读的值
     * （''、'0'、首尾空白、不满足 content 规则——09 方案第 7.2.5 节）；最多 {@link #MAX_CHOICES} 个。
     * 当前值不在候选中时也列出（标记为当前）。没有任何链接（除"全部"）时不显示该组。
     */
    private void addGroup(List<FilterGroup> groups, SearchFilters f, String key, String label, String current,
                          List<CategoryExtendDto> extend, Function<CategoryExtendDto, String> field) {
        Set<String> values = new LinkedHashSet<>();
        for (CategoryExtendDto e : extend) {
            String raw = field.apply(e);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            for (String v : raw.split(",", -1)) {
                if (values.size() >= MAX_CHOICES) {
                    break;
                }
                if (!values.contains(v) && linkable(f, key, v)) {
                    values.add(v);
                }
            }
        }
        List<FilterChip> chips = new ArrayList<>();
        if (current != null && !values.contains(current)) {
            chips.add(new FilterChip(current, link(f, 1), true));
        }
        for (String v : values) {
            chips.add(new FilterChip(v, link(f.with(key, v), 1), v.equals(current)));
        }
        if (chips.isEmpty()) {
            return;
        }
        chips.addFirst(new FilterChip("全部", link(f.with(key, null), 1), current == null));
        groups.add(new FilterGroup(key, label, List.copyOf(chips)));
    }

    private static boolean linkable(SearchFilters f, String key, String v) {
        try {
            f.with(key, v);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void collectExtend(List<CategoryDto> nodes, List<CategoryExtendDto> out) {
        for (CategoryDto c : nodes) {
            if (c.extend() != null) {
                out.add(c.extend());
            }
            collectExtend(c.children(), out);
        }
    }

    private static String sortLabel(VideoSort s) {
        return switch (s) {
            case HITS -> "人气";
            case SCORE -> "评分";
            default -> "时间";
        };
    }
}

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
import com.maccms.backend.site.application.LegacyTextEntities;
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
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * SSR 搜索页的视图模型组装（09 方案第 7.3、7.4 节）。只调用 {@link ContentReadService} 的只读方法与
 * {@code content.application.view} 类型；所有链接由 {@link LegacyUrlBuilder#vodSearchLink} 生成固定路径 + 查询串，
 * 经 {@link SiteSettings#publicPath(SiteLink)} / {@link SiteSettings#canonical(SiteLink)} 成为 {@link SafeUrl}。
 *
 * <ul>
 * <li>无任何条件：不查库，只显示表单与提示；带页码（page&gt;1）→ 404。</li>
 * <li>有条件：一次 {@code listCategories()}（导航与筛选候选值）+ 一次 {@code listVideos}（每页 20 条，默认 time desc）。
 * 带包含匹配条件（wd、class、actor、director、tag、name）而 {@code maccms.content.search.enabled=false} 时在查库前就是
 * INVALID_PARAMETER（400）。tid 经 content 的内部条件（{@code type_id = tid OR type_id_1 = tid}），可见性规则不变。</li>
 * <li>越界页（page&gt;1 且 total=0，或 page&gt;totalPages）→ 404；随机排序（by=rnd）按旧算法取页，不判越界、不显示分页
 * （15 文档第 3.4 节）；查询失败的异常原样抛出（503/500 由 SiteExceptionHandler 处理）。</li>
 * </ul>
 *
 * <p>筛选区（B05 契约稿，15 文档第 3.3 节，待前端确认样式）：地区、语言、年份、首字母、状态、版本（精确匹配，候选值来自分类扩展字段，
 * 首字母为 A–Z 与 0-9）；类型、主演、导演（包含匹配，只在开关打开时出现）；标签、片名、指定视频、分类（tid）没有候选值，只在已设置时
 * 显示"全部 + 当前值"；最后是排序。全部沿用 MACC-20 的 filter-group / filter-chip 结构与 class，没有新组件。
 */
public class SearchPages {

    /** 每页条数，与分类页、契约 pageSize 默认值一致。 */
    static final int PAGE_SIZE = VideoListQuery.DEFAULT_PAGE_SIZE;
    /** 每组筛选候选值的上限（不含"全部"与不在候选中的当前值）。 */
    static final int MAX_CHOICES = 30;
    /** 排序链接：时间、人气、评分（其余 by 值与 rnd 仍可经 URL 使用）。 */
    static final List<VideoSort> SORT_LINKS = List.of(VideoSort.TIME, VideoSort.HITS, VideoSort.SCORE);
    /** 首字母候选值：A–Z 与 0-9（旧 letter 的 0?9 写法展开为 0..9，Vod.php:330-335）。 */
    static final List<String> LETTERS;

    static {
        List<String> l = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            l.add(String.valueOf(c));
        }
        l.add("0-9");
        LETTERS = List.copyOf(l);
    }

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

    /**
     * 页面的基址：搜索页（{@code show == null}）或分类筛选页（旧 vod/show，D-B05-7）。所有链接（canonical、表单、筛选、分页）
     * 都由同一个函数生成，所以后续筛选与翻页始终保留分类与其余条件。
     */
    private record Base(Integer show, BiFunction<SearchFilters, Integer, SiteLink> links) {

        SiteLink link(SearchFilters f, int page) {
            return links.apply(f, page);
        }
    }

    public SearchPage search(SiteRoute.VodSearch route) {
        Base base = new Base(null, urls::vodSearchLink);
        SearchFilters f = route.filters();
        int page = route.page();
        if (f.isEmpty()) {
            if (page > 1) {
                throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
            }
            return new SearchPage(pages.search("搜索视频 - " + site.name(),
                    Optional.of(site.canonical(base.link(f, page))), List.of()), "search", "搜索视频", false,
                    formAction(base), "", List.of(), Optional.empty(), List.of(), List.of(), Optional.empty(), false);
        }
        List<CategoryDto> roots = checkedRoots(f);
        String heading = f.keyword() != null ? "搜索：" + f.keyword() : "筛选结果";
        return render(base, f, page, null, roots, heading, 0, extendOf(roots));
    }

    /**
     * 分类筛选页（旧 vod/show，Vod.php:23-28；D-B05-7）：分类本身 + 直接子分类（与 API categoryId、分类页相同，可见性与发布约束不变），
     * 叠加搜索页的条件；无条件时列出该分类的全部内容。分类不存在或不可见 → 404。开关（旧 app.show）由 Controller 判断。
     */
    public SearchPage show(SiteRoute.VodShow route) {
        int category = route.categoryId();
        Base base = new Base(category, (f, page) -> urls.vodShowLink(category, f, page));
        SearchFilters f = route.filters();
        List<CategoryDto> roots = checkedRoots(f);
        List<CategoryDto> chain = ContentPages.chain(roots, category);
        if (chain.isEmpty()) {
            throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
        }
        String name = LegacyTextEntities.decodeOnce(chain.getLast().name());
        String heading = f.keyword() != null ? name + "：" + f.keyword() : name;
        // 旧筛选页的候选值来自当前分类的扩展字段（模板读 $obj.type_extend）；没有时用其上级分类的
        List<CategoryExtendDto> extend = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0 && extend.isEmpty(); i--) {
            if (chain.get(i).extend() != null) {
                extend.add(chain.get(i).extend());
            }
        }
        return render(base, f, route.page(), category, roots, heading, chain.getFirst().id(), extend);
    }

    /** 包含匹配条件而搜索开关关闭 → 400（与 content 的判断相同，提前到查库之前）；否则读一次分类（导航与候选值）。 */
    private List<CategoryDto> checkedRoots(SearchFilters f) {
        if (f.usesContainsMatch() && !content.searchEnabled()) {
            throw ApiException.invalidParameter(f.firstContainsMatchParameter(), "当前未开放关键字搜索");
        }
        return content.listCategories().items();
    }

    private SearchPage render(Base base, SearchFilters f, int page, Integer categoryId, List<CategoryDto> roots,
                              String heading, int currentNav, List<CategoryExtendDto> extend) {
        // 随机排序：旧版改写为 hits_week desc（Vod.php:532-535），契约 1.3.0 的 VideoListQuery.random 照搬两种取页算法
        VideoSort sort = f.random() ? VideoSort.HITS_WEEK : f.sort() == null ? VideoSort.TIME : f.sort();
        boolean descending = f.random() || f.order() != SearchFilters.SortOrder.ASC;
        PageResponse<VideoSummaryDto> result = content.listVideos(new VideoListQuery(categoryId, sort, descending, page,
                PAGE_SIZE, f.toVideoFilter(), f.random(), f.toLegacyConditions()));
        boolean outOfRange = result.total() == 0 ? page > 1 : page > result.totalPages();
        if (outOfRange && !f.random()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        String title = heading + (page > 1 ? " - 第" + page + "页" : "") + " - " + site.name();
        int pageCount = (int) Math.min(result.totalPages(), VideoListQuery.MAX_PAGE);
        Optional<PaginationView> pagination = Optional.empty();
        // 随机排序每次取的页不同，上一页/下一页没有意义（旧版同样以随机页覆盖请求的页码），不显示分页
        if (pageCount > 1 && !f.random()) {
            pagination = Optional.of(new PaginationView(page, pageCount,
                    page > 1 ? Optional.of(link(base, f, page - 1)) : Optional.empty(),
                    page < pageCount ? Optional.of(link(base, f, page + 1)) : Optional.empty()));
        }
        SafeUrl canonical = site.canonical(base.link(f, page));
        return new SearchPage(pages.search(title, Optional.of(canonical), contentPages.nav(roots, currentNav)),
                base.show() == null ? "search" : "show", heading, false, formAction(base),
                f.keyword() == null ? "" : f.keyword(), hiddenFields(f), Optional.of(result.total()),
                filterGroups(base, f, roots, extend), contentPages.cards(result.items()), pagination, f.random());
    }

    /** 参数不合法（400）：固定提示、空白表单；不查库、不回显输入、没有 canonical。 */
    public SearchPage invalid() {
        return new SearchPage(pages.search("搜索条件不合法 - " + site.name(), Optional.empty(), List.of()),
                "search", "搜索条件不合法", true, formAction(new Base(null, urls::vodSearchLink)), "", List.of(),
                Optional.empty(), List.of(), List.of(), Optional.empty(), false);
    }

    private SafeUrl formAction(Base base) {
        return site.publicPath(base.link(SearchFilters.NONE, 1));
    }

    private SafeUrl link(Base base, SearchFilters f, int page) {
        return site.publicPath(base.link(f, page));
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

    private static List<CategoryExtendDto> extendOf(List<CategoryDto> roots) {
        List<CategoryExtendDto> extend = new ArrayList<>();
        collectExtend(roots, extend);
        return extend;
    }

    private List<FilterGroup> filterGroups(Base base, SearchFilters f, List<CategoryDto> roots,
                                           List<CategoryExtendDto> extend) {
        List<FilterGroup> groups = new ArrayList<>();
        addGroup(base, groups, f, "area", "地区", f.area(), candidates(extend, CategoryExtendDto::area));
        addGroup(base, groups, f, "lang", "语言", f.lang(), candidates(extend, CategoryExtendDto::lang));
        addGroup(base, groups, f, "year", "年份", f.year(), candidates(extend, CategoryExtendDto::year));
        addGroup(base, groups, f, "letter", "首字母", f.letter(), LETTERS);
        addGroup(base, groups, f, "state", "状态", f.state(), candidates(extend, CategoryExtendDto::state));
        addGroup(base, groups, f, "version", "版本", f.version(), candidates(extend, CategoryExtendDto::version));
        if (content.searchEnabled()) {
            // 包含匹配，开关关闭时点了只会得到 400，不提供链接
            addGroup(base, groups, f, "class", "类型", f.classes(), candidates(extend, CategoryExtendDto::classes));
            addGroup(base, groups, f, "actor", "主演", f.actor(), candidates(extend, CategoryExtendDto::star));
            addGroup(base, groups, f, "director", "导演", f.director(), candidates(extend, CategoryExtendDto::director));
        }
        // 没有候选值的条件：只在已设置时显示"全部 + 当前值"，便于去掉（开关关闭时这些包含匹配条件在查库前已是 400）
        addGroup(base, groups, f, "tag", "标签", f.tag(), List.of());
        addGroup(base, groups, f, "name", "片名", f.name(), List.of());
        addGroup(base, groups, f, "ids", "指定视频", f.ids(), List.of());
        if (f.tid() != null) {
            groups.add(new FilterGroup("tid", "分类", List.of(
                    new FilterChip("全部", link(base, f.with("tid", null), 1), false),
                    new FilterChip(tidLabel(roots, f.tid()), link(base, f, 1), true))));
        }
        List<FilterChip> sorts = new ArrayList<>();
        for (VideoSort s : SORT_LINKS) {
            boolean current = !f.random() && (f.sort() == null ? s == VideoSort.TIME : f.sort() == s);
            sorts.add(new FilterChip(sortLabel(s), link(base, f.withSort(s), 1), current));
        }
        if (f.random()) {
            sorts.add(new FilterChip("随机", link(base, f, 1), true));
        }
        groups.add(new FilterGroup("by", "排序", List.copyOf(sorts)));
        return List.copyOf(groups);
    }

    /** tid 的显示：可见分类的名称；不可见或不存在时为编号（旧版照样筛选，结果只含可见分类中 type_id_1 等于它的行）。 */
    private static String tidLabel(List<CategoryDto> roots, long tid) {
        if (tid >= 1 && tid <= VideoListQuery.MAX_CATEGORY_ID) {
            List<CategoryDto> chain = ContentPages.chain(roots, (int) tid);
            if (!chain.isEmpty()) {
                return LegacyTextEntities.decodeOnce(chain.getLast().name());
            }
        }
        return "分类 " + tid;
    }

    /**
     * 所有可见分类 extend 中该键的值的并集（逗号分隔，去重、保持出现顺序），未经可回读检查；{@link #addGroup} 再按
     * {@link #MAX_CHOICES} 截取可回读的值。
     */
    private static List<String> candidates(List<CategoryExtendDto> extend, Function<CategoryExtendDto, String> field) {
        Set<String> values = new LinkedHashSet<>();
        for (CategoryExtendDto e : extend) {
            String raw = field.apply(e);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            values.addAll(List.of(raw.split(",", -1)));
        }
        return List.copyOf(values);
    }

    /**
     * 一组候选值：跳过不能作为条件回读的值（''、'0'、首尾空白、不满足 content 规则或超出链接预算——09 方案第 7.2.5 节）；
     * 最多 {@link #MAX_CHOICES} 个。当前值不在候选中时也列出（标记为当前）。没有任何链接（除"全部"）时不显示该组。
     */
    private void addGroup(Base base, List<FilterGroup> groups, SearchFilters f, String key, String label,
                          String current, List<String> choices) {
        Set<String> values = new LinkedHashSet<>();
        for (String v : choices) {
            if (values.size() >= MAX_CHOICES) {
                break;
            }
            if (!values.contains(v) && linkable(f, key, v)) {
                values.add(v);
            }
        }
        List<FilterChip> chips = new ArrayList<>();
        if (current != null && !values.contains(current)) {
            chips.add(new FilterChip(current, link(base, f, 1), true));
        }
        for (String v : values) {
            chips.add(new FilterChip(v, link(base, f.with(key, v), 1), v.equals(current)));
        }
        if (chips.isEmpty()) {
            return;
        }
        chips.addFirst(new FilterChip("全部", link(base, f.with(key, null), 1), current == null));
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

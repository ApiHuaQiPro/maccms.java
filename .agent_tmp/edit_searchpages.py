p = 'backend/src/main/java/com/maccms/backend/site/web/SearchPages.java'
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new)


rep(''' * <ul>
 * <li>无任何条件：不查库，只显示表单与提示；带页码（page&gt;1）→ 404。</li>
 * <li>有条件：一次 {@code listCategories()}（导航与筛选候选值）+ 一次 {@code listVideos}（每页 20 条，默认 time desc）。
 * 带 keyword/classes 而 {@code maccms.content.search.enabled=false} 时在查库前就是 INVALID_PARAMETER（400）。</li>
 * <li>越界页（page&gt;1 且 total=0，或 page&gt;totalPages）→ 404；查询失败的异常原样抛出（503/500 由 SiteExceptionHandler 处理）。</li>
 * </ul>
 */''', ''' * <ul>
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
 */''')

rep('''    /** 排序链接：时间、人气、评分（其余 by 值仍可经 URL 使用）。 */
    static final List<VideoSort> SORT_LINKS = List.of(VideoSort.TIME, VideoSort.HITS, VideoSort.SCORE);''',
'''    /** 排序链接：时间、人气、评分（其余 by 值与 rnd 仍可经 URL 使用）。 */
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
    }''')

rep('''        if (f.usesContainsMatch() && !content.searchEnabled()) {
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
        if (pageCount > 1) {''', '''        if (f.usesContainsMatch() && !content.searchEnabled()) {
            // 与 content 的判断相同（ContentReadService.listVideos），这里提前到查库之前
            throw ApiException.invalidParameter(f.firstContainsMatchParameter(), "当前未开放关键字搜索");
        }
        List<CategoryDto> roots = content.listCategories().items();
        // 随机排序：旧版改写为 hits_week desc（Vod.php:532-535），契约 1.3.0 的 VideoListQuery.random 照搬两种取页算法
        VideoSort sort = f.random() ? VideoSort.HITS_WEEK : f.sort() == null ? VideoSort.TIME : f.sort();
        boolean descending = f.random() || f.order() != SearchFilters.SortOrder.ASC;
        PageResponse<VideoSummaryDto> result = content.listVideos(new VideoListQuery(null, sort, descending, page,
                PAGE_SIZE, f.toVideoFilter(), f.random(), f.toLegacyConditions()));
        boolean outOfRange = result.total() == 0 ? page > 1 : page > result.totalPages();
        if (outOfRange && !f.random()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }

        String heading = f.keyword() != null ? "搜索：" + f.keyword() : "筛选结果";
        String title = heading + (page > 1 ? " - 第" + page + "页" : "") + " - " + site.name();
        int pageCount = (int) Math.min(result.totalPages(), VideoListQuery.MAX_PAGE);
        Optional<PaginationView> pagination = Optional.empty();
        // 随机排序每次取的页不同，上一页/下一页没有意义（旧版同样以随机页覆盖请求的页码），不显示分页
        if (pageCount > 1 && !f.random()) {''')

rep('''    private List<FilterGroup> filterGroups(SearchFilters f, List<CategoryDto> roots) {
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
    }''', '''    private List<FilterGroup> filterGroups(SearchFilters f, List<CategoryDto> roots) {
        List<CategoryExtendDto> extend = new ArrayList<>();
        collectExtend(roots, extend);
        List<FilterGroup> groups = new ArrayList<>();
        addGroup(groups, f, "area", "地区", f.area(), candidates(extend, CategoryExtendDto::area));
        addGroup(groups, f, "lang", "语言", f.lang(), candidates(extend, CategoryExtendDto::lang));
        addGroup(groups, f, "year", "年份", f.year(), candidates(extend, CategoryExtendDto::year));
        addGroup(groups, f, "letter", "首字母", f.letter(), LETTERS);
        addGroup(groups, f, "state", "状态", f.state(), candidates(extend, CategoryExtendDto::state));
        addGroup(groups, f, "version", "版本", f.version(), candidates(extend, CategoryExtendDto::version));
        if (content.searchEnabled()) {
            // 包含匹配，开关关闭时点了只会得到 400，不提供链接
            addGroup(groups, f, "class", "类型", f.classes(), candidates(extend, CategoryExtendDto::classes));
            addGroup(groups, f, "actor", "主演", f.actor(), candidates(extend, CategoryExtendDto::star));
            addGroup(groups, f, "director", "导演", f.director(), candidates(extend, CategoryExtendDto::director));
        }
        // 没有候选值的条件：只在已设置时显示"全部 + 当前值"，便于去掉（开关关闭时这些包含匹配条件在查库前已是 400）
        addGroup(groups, f, "tag", "标签", f.tag(), List.of());
        addGroup(groups, f, "name", "片名", f.name(), List.of());
        addGroup(groups, f, "ids", "指定视频", f.ids(), List.of());
        if (f.tid() != null) {
            groups.add(new FilterGroup("tid", "分类", List.of(
                    new FilterChip("全部", link(f.with("tid", null), 1), false),
                    new FilterChip(tidLabel(roots, f.tid()), link(f, 1), true))));
        }
        List<FilterChip> sorts = new ArrayList<>();
        for (VideoSort s : SORT_LINKS) {
            boolean current = !f.random() && (f.sort() == null ? s == VideoSort.TIME : f.sort() == s);
            sorts.add(new FilterChip(sortLabel(s), link(f.withSort(s), 1), current));
        }
        if (f.random()) {
            sorts.add(new FilterChip("随机", link(f, 1), true));
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
    }''')

rep('''    /**
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
        }''', '''    /**
     * 一组候选值：跳过不能作为条件回读的值（''、'0'、首尾空白、不满足 content 规则或超出链接预算——09 方案第 7.2.5 节）；
     * 最多 {@link #MAX_CHOICES} 个。当前值不在候选中时也列出（标记为当前）。没有任何链接（除"全部"）时不显示该组。
     */
    private void addGroup(List<FilterGroup> groups, SearchFilters f, String key, String label, String current,
                          List<String> choices) {
        Set<String> values = new LinkedHashSet<>();
        for (String v : choices) {
            if (values.size() >= MAX_CHOICES) {
                break;
            }
            if (!values.contains(v) && linkable(f, key, v)) {
                values.add(v);
            }
        }''')

rep('''import com.maccms.backend.site.application.LegacyUrlBuilder;''', '''import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')

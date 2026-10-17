p = 'backend/src/main/java/com/maccms/backend/content/application/ContentReadService.java'
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert old in s, old[:80]
    s = s.replace(old, new)


rep("""    public PageResponse<VideoSummaryDto> listVideos(VideoListQuery query) {
        VideoFilter filter = query.filter();
        if (!filter.usesContainsMatch()) {
            return read(() -> doListVideos(query));
        }
        if (!search.enabled()) {
            throw ApiException.invalidParameter(filter.firstContainsMatchParameter(), VideoFilter.SEARCH_DISABLED);
        }""", """    public PageResponse<VideoSummaryDto> listVideos(VideoListQuery query) {
        VideoFilter filter = query.filter();
        VideoLegacyConditions legacy = query.legacy();
        // B05：旧标签的包含匹配条件（weekday、文本 rel）与 keyword 等共用开关、并发许可与执行时限，内部能力不绕过成本护栏
        if (!filter.usesContainsMatch() && !legacy.usesContainsMatch()) {
            return read(() -> doListVideos(query));
        }
        if (!search.enabled()) {
            String field = filter.usesContainsMatch() ? filter.firstContainsMatchParameter()
                    : legacy.firstContainsMatchParameter();
            throw ApiException.invalidParameter(field, VideoFilter.SEARCH_DISABLED);
        }""")

rep("""    private PageResponse<VideoSummaryDto> doListVideos(VideoListQuery query) {
        CategoryCatalog catalog = loadCatalog(loadGuest());
        Set<Integer> typeIds;
        if (query.categoryId() != null) {
            if (!catalog.isVisible(query.categoryId())) {
                throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
            }
            typeIds = catalog.idsForFilter(query.categoryId());
        } else {
            typeIds = catalog.allIds();
        }
        if (typeIds.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), 0);
        }
        VodFilterCriteria criteria = criteria(query.filter());""", """    private PageResponse<VideoSummaryDto> doListVideos(VideoListQuery query) {
        CategoryCatalog catalog = loadCatalog(loadGuest());
        Set<Integer> typeIds = typeIds(catalog, query);
        if (typeIds.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), 0);
        }
        VodFilterCriteria criteria = criteria(query.filter(), query.legacy());""")

rep("""    /**
     * 随机排序（旧 Vod.php:500-537）。""", """    /**
     * 本次查询的分类范围（始终在可见分类内）。旧版 {@code $where['type_id']} 只有一个：typenot（Vod.php:352-354）覆盖 type
     * 与当前分类（:337-351；对应 {@code categoryId} 与 {@link VideoLegacyConditions#types()}），二者都是"本身与直接子分类"。
     * {@code categoryId} 不可见时为 CATEGORY_NOT_FOUND（1.0.0 起的公开 API 规则）；types 中不可见的项只是不命中。
     */
    static Set<Integer> typeIds(CategoryCatalog catalog, VideoListQuery query) {
        VideoLegacyConditions legacy = query.legacy();
        if (legacy.typeNot() != null) {
            Set<Integer> out = new LinkedHashSet<>(catalog.allIds());
            out.removeAll(legacy.typeNot());
            return out;
        }
        if (query.categoryId() != null) {
            if (!catalog.isVisible(query.categoryId())) {
                throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
            }
            return catalog.idsForFilter(query.categoryId());
        }
        if (legacy.types() != null) {
            Set<Integer> out = new LinkedHashSet<>();
            for (int t : legacy.types()) {
                if (catalog.isVisible(t)) {
                    out.add(t);
                }
                // 旧版 type_pid 命中即列入：0 即全部顶级分类；不可见分类的子分类本身也不可见（上级链不完整）
                for (TypeRow child : catalog.childrenOf(t)) {
                    out.add(child.id());
                }
            }
            return out;
        }
        return catalog.allIds();
    }

    /**
     * 随机排序（旧 Vod.php:500-537）。""")

rep("""        long page = random.pageBetweenOneAnd(count / query.pageSize() + 1);
        long offset = (page - 1) * query.pageSize();""", """        long page = random.pageBetweenOneAnd(count / query.pageSize() + 1);
        long offset = query.offsetOf(page);""")

rep("""    static VodFilterCriteria criteria(VideoFilter f) {
        if (f.isEmpty()) {
            return null;
        }
        List<String> years = f.years();
        List<String> areas = f.areas();
        List<String> langs = f.langs();
        List<String> classes = f.classesPatterns();
        return new VodFilterCriteria(f.level(), single(years), single(areas), single(langs), single(classes),
                f.keywordPatterns(), multiple(years), f.letters(), f.states(), f.versions(), f.actorPatterns(),
                f.directorPatterns(), f.tagPatterns(), f.namePatterns(), f.idList(), null, multiple(areas),
                multiple(langs), multiple(classes));
    }""", """    static VodFilterCriteria criteria(VideoFilter f) {
        return criteria(f, VideoLegacyConditions.NONE);
    }

    /**
     * 同上，另加 B05 的旧标签条件。旧版 ids、not、数字 rel 写入同一个 {@code $where['vod_id']}（Vod.php:273-284，后写的覆盖先写的）：
     * 数字 rel 设置时 {@code vod_id IN (rel)}；否则 not 设置时 {@code vod_id NOT IN (not)}；否则才是 ids。
     */
    static VodFilterCriteria criteria(VideoFilter f, VideoLegacyConditions l) {
        boolean idsOverridden = l.relIds() != null || l.notIds() != null;
        List<Long> ids = l.relIds() != null ? l.relIds() : idsOverridden ? null : f.idList();
        VodLegacyCriteria legacy = legacyCriteria(l);
        VideoFilter rest = idsOverridden ? f.withoutIds() : f;
        if (rest.isEmpty() && legacy == null && ids == null) {
            return null;
        }
        List<String> years = f.years();
        List<String> areas = f.areas();
        List<String> langs = f.langs();
        List<String> classes = f.classesPatterns();
        return new VodFilterCriteria(f.level(), single(years), single(areas), single(langs), single(classes),
                f.keywordPatterns(), multiple(years), f.letters(), f.states(), f.versions(), f.actorPatterns(),
                f.directorPatterns(), f.tagPatterns(), f.namePatterns(), ids, null, multiple(areas),
                multiple(langs), multiple(classes), legacy);
    }

    private static VodLegacyCriteria legacyCriteria(VideoLegacyConditions l) {
        List<Long> notIn = l.relIds() != null ? null : l.notIds();
        if (l.tid() == null && notIn == null && l.rel() == null && l.levels() == null && l.weekday() == null
                && l.tvs() == null && l.addedAfter() == null && l.hitsTimeAfter() == null && l.updatedAfter() == null
                && l.hitsMonth() == null && l.hitsWeek() == null && l.hitsDay() == null && l.hits() == null
                && l.isEnd() == null && l.plot() == null) {
            return null;
        }
        return new VodLegacyCriteria(l.tid(), notIn, l.relPatterns(), l.levels(), l.weekdayPatterns(), l.tvs(),
                l.addedAfter(), l.hitsTimeAfter(), l.updatedAfter(), range(l.hitsMonth()), range(l.hitsWeek()),
                range(l.hitsDay()), range(l.hits()), l.isEnd(), l.plot());
    }

    private static VodRangeCriteria range(LegacyRange r) {
        return r == null ? null : new VodRangeCriteria(r.op().name(), r.values());
    }""")

rep("""import com.maccms.backend.content.infrastructure.VodIdRange;
""", """import com.maccms.backend.content.infrastructure.VodIdRange;
import com.maccms.backend.content.infrastructure.VodLegacyCriteria;
""")
rep("""import com.maccms.backend.content.infrastructure.VodMapper;
""", """import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodRangeCriteria;
""")
rep("""import java.util.IdentityHashMap;
""", """import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
""")
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')

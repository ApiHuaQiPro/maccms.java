package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.CategoryExtendDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.CategoryRefDto;
import com.maccms.backend.content.application.view.VideoDetailDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.StatementTimeouts;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.TypeRow;
import com.maccms.backend.content.infrastructure.VodDetailRow;
import com.maccms.backend.content.infrastructure.VodFilterCriteria;
import com.maccms.backend.content.infrastructure.VodIdRange;
import com.maccms.backend.content.infrastructure.VodLegacyCriteria;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodRangeCriteria;
import com.maccms.backend.content.infrastructure.VodSummaryRow;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * content 模块的只读查询。只调用 Mapper 的 select，不写任何表，不写缓存。
 * 可见性规则见 CategoryCatalog 与 docs/architecture/01-batch1-content-read-plan.md 第 4 节。
 *
 * <p>数据库连接类故障（异常本身或 cause 链中有 {@link DataAccessResourceFailureException}、
 * {@link TransientDataAccessException}，类型与 GlobalExceptionHandler 原有的 503 判定相同）在这里统一转换为
 * {@code ApiException(SERVICE_UNAVAILABLE)}，
 * 调用方（公开 API、site 页面）不需要、也不应依赖 Spring DAO 类型（MACC-14）。对外 JSON 与转换前相同：
 * {@code {"code":"SERVICE_UNAVAILABLE","message":"服务暂时不可用","fieldErrors":[]}}；日志沿用 "database unavailable"
 * + 类型链，不含 message。其他数据访问异常不转换，仍由全局处理返回 500。
 *
 * <p>搜索/筛选（契约 1.1.0，09 方案第 6.3 节）：带 keyword/classes 的请求要求 {@code maccms.content.search.enabled=true}
 * （否则 400），并经进程内并发上限（{@code max-concurrent}，不等待）进入，拿不到许可即 503；带任一新条件的语句有服务端
 * 执行时限，超时（MySQL 3024；排序阶段中止为 1028）同样归为 503。两种新 503 的响应体与数据库不可用时逐字相同，日志只有固定说明与类型链，
 * 不记录条件值。不带新条件的请求不经过这两项，SQL 与 1.0.0 相同。1.3.0 的 actor/director/tag/name 同样是包含匹配，与
 * keyword/classes 共用开关、并发许可与 503；letter/state/version/ids/年份区间是精确匹配，只受执行时限约束。
 *
 * <p>随机排序（1.3.0 sort=rnd，{@link VideoListQuery#random()}）照搬旧 Vod::listCacheData 的两种算法
 * （application/common/model/Vod.php:500-537，阈值 {@value #RANDOM_THRESHOLD}），见 {@link #randomPage}。
 */
@Service
public class ContentReadService {

    private static final Logger log = LoggerFactory.getLogger(ContentReadService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 与 GlobalExceptionHandler 对连接类故障返回的 message 相同。 */
    static final String UNAVAILABLE_MESSAGE = "服务暂时不可用";
    private static final int MAX_CAUSE_DEPTH = 8;

    private final TypeMapper typeMapper;
    private final GroupMapper groupMapper;
    private final VodMapper vodMapper;
    private final ContentProperties properties;
    private final SearchSettings search;
    private final Semaphore searchPermits;
    private final VideoRandom random;

    /** 旧版随机排序在两种算法之间切换的行数（Vod.php:505 {@code $algo2_threshold}）：超过它才取随机 ID 段。 */
    static final int RANDOM_THRESHOLD = 2000;
    /** 随机 ID 段的候选个数（Vod.php:518 {@code intval($algo2_threshold / 2)}）。 */
    static final int RANDOM_CANDIDATES = RANDOM_THRESHOLD / 2;

    /** 连接池大小取 Hikari 的同一配置（application.yml 中 MACCMS_DB_POOL_SIZE，默认 10），用于校验并发上限。 */
    @Autowired
    public ContentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper,
                              ContentProperties properties,
                              @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize,
                              ObjectProvider<VideoRandom> random) {
        this(typeMapper, groupMapper, vodMapper, properties, poolSize, random.getIfAvailable(VideoRandom::new));
    }

    /** 随机源可替换（测试用确定的实现覆盖随机排序的每个分支）。 */
    public ContentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper,
                              ContentProperties properties, int poolSize, VideoRandom random) {
        this.typeMapper = typeMapper;
        this.groupMapper = groupMapper;
        this.vodMapper = vodMapper;
        this.properties = properties;
        this.search = SearchSettings.from(properties.search(), poolSize);
        this.searchPermits = new Semaphore(search.maxConcurrent());
        this.random = random;
    }

    public ContentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper,
                              ContentProperties properties, int poolSize) {
        this(typeMapper, groupMapper, vodMapper, properties, poolSize, new VideoRandom());
    }

    /** 测试与无 Spring 场景：连接池按默认 10。 */
    public ContentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper,
                              ContentProperties properties) {
        this(typeMapper, groupMapper, vodMapper, properties, 10);
    }

    /** 是否接受包含匹配参数 keyword、classes、actor、director、tag、name（{@code maccms.content.search.enabled}）。 */
    public boolean searchEnabled() {
        return search.enabled();
    }

    public CategoryListResponse listCategories() {
        return read(() -> {
            CategoryCatalog catalog = loadCatalog(loadGuest());
            return new CategoryListResponse(toTree(catalog, catalog.roots()));
        });
    }

    public PageResponse<VideoSummaryDto> listVideos(VideoListQuery query) {
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
        }
        if (!searchPermits.tryAcquire()) {
            log.warn("content search rejected: concurrency limit reached");
            throw unavailable();
        }
        try {
            return read(() -> doListVideos(query));
        } finally {
            searchPermits.release();
        }
    }

    public VideoDetailDto getVideo(long id) {
        return read(() -> doGetVideo(id));
    }

    /**
     * 包含匹配查询的进程内并发许可（与带 keyword/classes 的视频列表共用同一个上限，契约 1.2.0 专题按标签关联内容也经过这里）。
     * 搜索开关关闭时调用方不应进入包含匹配查询。拿到许可后必须调用 {@link #releaseSearchPermit()}。
     */
    boolean tryAcquireSearchPermit() {
        return searchPermits.tryAcquire();
    }

    void releaseSearchPermit() {
        searchPermits.release();
    }

    /**
     * 执行一次只读查询，把数据库连接类故障与服务端执行时限超时（搜索/筛选语句的 MAX_EXECUTION_TIME）转换为
     * SERVICE_UNAVAILABLE；其他异常原样抛出。
     */
    static <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            if (isConnectionFailure(e)) {
                log.error("database unavailable: {}", SafeExceptionLog.typeChain(e));
                throw unavailable();
            }
            if (!StatementTimeouts.isServerTimeout(e)) {
                throw e;
            }
            log.warn("database statement timeout: {}", SafeExceptionLog.typeChain(e));
            throw unavailable();
        }
    }

    /**
     * 异常本身或其 cause 链（最多 8 层）中有连接类故障。真实的连接失败（连接被拒绝、连接池取不到连接）经 MyBatis 包装后是
     * MyBatisSystemException ← PersistenceException ← CannotGetJdbcConnectionException，外层并不是连接类故障类型（MACC-14）。
     */
    static boolean isConnectionFailure(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (t instanceof DataAccessResourceFailureException || t instanceof TransientDataAccessException) {
                return true;
            }
        }
        return false;
    }

    static ApiException unavailable() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }

    private PageResponse<VideoSummaryDto> doListVideos(VideoListQuery query) {
        CategoryCatalog catalog = loadCatalog(loadGuest());
        Set<Integer> typeIds = typeIds(catalog, query);
        if (typeIds.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), 0);
        }
        VodFilterCriteria criteria = criteria(query.filter(), query.legacy());
        if (query.random()) {
            return randomPage(catalog, typeIds, criteria, query);
        }
        long total = criteria == null ? vodMapper.countPublished(typeIds)
                : vodMapper.countPublishedMatching(typeIds, criteria);
        if (total == 0 || query.offset() >= total) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), total);
        }
        List<VodSummaryRow> rows = criteria == null
                ? vodMapper.selectPublishedPage(typeIds, query.sort(), query.descending(), query.offset(),
                        query.pageSize())
                : vodMapper.selectPublishedMatchingPage(typeIds, criteria, query.sort(), query.descending(),
                        query.offset(), query.pageSize());
        List<VideoSummaryDto> items = rows.stream().map(r -> toSummary(r, catalog)).toList();
        return PageResponse.of(items, query.page(), query.pageSize(), total);
    }

    /**
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
     * 随机排序（旧 Vod.php:500-537）。先按全部条件计数 c：
     * <ul>
     *   <li>c &gt; {@value #RANDOM_THRESHOLD} 且全表 MIN/MAX(vod_id) 有效（最小值非 0、最大值大于最小值）时：从下标
     *       0..(max−min) 中随机取 {@value #RANDOM_CANDIDATES} 个作为候选 vod_id（旧版 array_rand 返回的是 range 数组的键，
     *       不是 ID，照搬：候选范围是 0..max−min，min 很大或 ID 稀疏时可能一个都命中不了），与原条件 AND 后按 hits_week 倒序，
     *       取请求的 page 页，total 为加上候选段后的计数；</li>
     *   <li>否则：随机页 = 1..(c / pageSize + 1)（整除，c 恰为页大小整数倍时最后一个随机页为空，与旧版相同），忽略请求的 page，
     *       按 hits_week 倒序取该页，响应中的 page 为实际取的随机页，total 为 c。</li>
     * </ul>
     * 两种情况都追加 vod_id 倒序（并列时稳定，旧版没有）。
     */
    private PageResponse<VideoSummaryDto> randomPage(CategoryCatalog catalog, Set<Integer> typeIds,
                                                     VodFilterCriteria criteria, VideoListQuery query) {
        long count = criteria == null ? vodMapper.countPublished(typeIds)
                : vodMapper.countPublishedMatching(typeIds, criteria);
        if (count > RANDOM_THRESHOLD) {
            VodIdRange range = vodMapper.selectIdRange();
            if (range != null && range.minId() != null && range.maxId() != null && range.minId() != 0
                    && range.maxId() > range.minId()) {
                long[] picked = random.sampleIndexes(range.maxId() - range.minId() + 1, RANDOM_CANDIDATES);
                List<Long> candidates = Arrays.stream(picked).boxed().toList();
                VodFilterCriteria c = criteria == null ? VodFilterCriteria.candidatesOnly(candidates)
                        : criteria.withCandidates(candidates);
                long total = vodMapper.countPublishedMatching(typeIds, c);
                if (total == 0 || query.offset() >= total) {
                    return PageResponse.of(List.of(), query.page(), query.pageSize(), total);
                }
                List<VodSummaryRow> rows = vodMapper.selectPublishedMatchingPage(typeIds, c, VideoSort.HITS_WEEK, true,
                        query.offset(), query.pageSize());
                return PageResponse.of(rows.stream().map(r -> toSummary(r, catalog)).toList(), query.page(),
                        query.pageSize(), total);
            }
        }
        long page = random.pageBetweenOneAnd(count / query.pageSize() + 1);
        long offset = query.offsetOf(page);
        int responsePage = Math.toIntExact(page);
        if (count == 0 || offset >= count) {
            return PageResponse.of(List.of(), responsePage, query.pageSize(), count);
        }
        List<VodSummaryRow> rows = criteria == null
                ? vodMapper.selectPublishedPage(typeIds, VideoSort.HITS_WEEK, true, offset, query.pageSize())
                : vodMapper.selectPublishedMatchingPage(typeIds, criteria, VideoSort.HITS_WEEK, true, offset,
                        query.pageSize());
        return PageResponse.of(rows.stream().map(r -> toSummary(r, catalog)).toList(), responsePage,
                query.pageSize(), count);
    }

    /**
     * 不带新条件时返回 null，使用与 1.0.0 相同的语句。单个年份仍用 {@code year}（1.1.0 的 SQL 文本不变），区间展开为多项时用
     * {@code years}；area、lang、classes 同理，只有一项时用 1.1.0 的单值形式，逗号多值时用 {@code areas}/{@code langs}/
     * {@code classesPatterns}。
     */
    static VodFilterCriteria criteria(VideoFilter f) {
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
    }

    private static String single(List<String> values) {
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private static List<String> multiple(List<String> values) {
        return values != null && values.size() > 1 ? values : null;
    }

    private VideoDetailDto doGetVideo(long id) {
        VodDetailRow row = vodMapper.selectPublishedDetail(id);
        if (row == null) {
            throw notFound();
        }
        GuestPermission guest = loadGuest();
        CategoryCatalog catalog = loadCatalog(guest);
        int categoryId = nz(row.categoryId());
        if (!catalog.isVisible(categoryId)) {
            throw notFound();
        }
        // 旧详情页对游客检查"详情"权限位 2（common/controller/All.php:408-414 → index/controller/Base.php:170-173）
        if (properties.enforceGuestPopedom() && !guest.allows(categoryId, GuestPermission.DETAIL)) {
            throw notFound();
        }
        // 旧详情页：vod_pwd 非空且本会话未验证时改为显示密码页（index/controller/Vod.php:61-63）
        if (nz(row.detailPasswordRequired()) != 0) {
            throw new ApiException(ErrorCode.VIDEO_PASSWORD_REQUIRED, "该视频详情需要观看密码");
        }
        // 旧详情页：vod_copyright=1 且 copyright_status=2 时改为显示版权页（index/controller/Vod.php:58-60）
        if (properties.copyrightHidesDetail() && nz(row.copyright()) == 1) {
            throw new ApiException(ErrorCode.VIDEO_COPYRIGHT_RESTRICTED, "该视频因版权原因不提供详情");
        }
        return toDetail(row, catalog);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在");
    }

    private GuestPermission loadGuest() {
        if (!properties.enforceGuestPopedom()) {
            return GuestPermission.from(null);
        }
        return GuestPermission.from(groupMapper.selectGroupPermission(ContentProperties.GUEST_GROUP_ID));
    }

    private CategoryCatalog loadCatalog(GuestPermission guest) {
        return CategoryCatalog.build(typeMapper.selectEnabledVodTypes(), guest, properties.enforceGuestPopedom());
    }

    /** 分类树（文章/演员/网址分类经 ContentCatalogs 复用）。 */
    static List<CategoryDto> toTree(CategoryCatalog catalog, List<TypeRow> rows) {
        return rows.stream().map(r -> new CategoryDto(
                r.id(), str(r.name()), str(r.enName()), nz(r.sort()), nz(r.parentId()),
                str(r.title()), str(r.keywords()), str(r.description()), str(r.logo()), str(r.pic()),
                parseExtend(r.extendJson()),
                toTree(catalog, catalog.childrenOf(r.id())))).toList();
    }

    static CategoryExtendDto parseExtend(String json) {
        JsonNode node = null;
        if (json != null && !json.isBlank()) {
            try {
                node = JSON.readTree(json);
            } catch (JacksonException e) {
                node = null;
            }
        }
        if (node == null || !node.isObject()) {
            return new CategoryExtendDto("", "", "", "", "", "", "", "");
        }
        return new CategoryExtendDto(text(node, "class"), text(node, "area"), text(node, "lang"),
                text(node, "year"), text(node, "star"), text(node, "director"), text(node, "state"),
                text(node, "version"));
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || v.isContainer()) {
            return "";
        }
        return v.asString();
    }

    private static CategoryRefDto ref(CategoryCatalog catalog, int categoryId) {
        TypeRow t = catalog.get(categoryId);
        return new CategoryRefDto(t.id(), str(t.name()), str(t.enName()), nz(t.parentId()));
    }

    /** 视频列表项（专题关联视频经 TopicReadService 复用）。 */
    static VideoSummaryDto toSummary(VodSummaryRow r, CategoryCatalog catalog) {
        return new VideoSummaryDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.classes()), str(r.tag()), str(r.pic()),
                str(r.picThumb()), str(r.picSlide()), str(r.actor()), str(r.director()), str(r.blurb()),
                str(r.remarks()), str(r.area()), str(r.lang()), str(r.year()), str(r.version()), str(r.state()),
                str(r.serial()), nz(r.total()), nz(r.isEnd()) == 1, nz(r.level()), nz(r.copyright()) == 1,
                dec(r.score()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()),
                nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()),
                nz(r.detailPasswordRequired()) != 0);
    }

    private static VideoDetailDto toDetail(VodDetailRow r, CategoryCatalog catalog) {
        return new VideoDetailDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.classes()), str(r.tag()), str(r.pic()),
                str(r.picThumb()), str(r.picSlide()), str(r.actor()), str(r.director()), str(r.writer()),
                str(r.behind()), str(r.blurb()), str(r.remarks()), str(r.pubdate()), str(r.area()), str(r.lang()),
                str(r.year()), str(r.version()), str(r.state()), str(r.tv()), str(r.weekday()), str(r.duration()),
                str(r.serial()), nz(r.total()), nz(r.isEnd()) == 1, nz(r.level()), nz(r.copyright()) == 1,
                dec(r.score()), nz(r.scoreAll()), nz(r.scoreNum()), nz(r.doubanId()), dec(r.doubanScore()),
                nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()),
                nz(r.updatedAt()), nz(r.addedAt()), str(r.content()));
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static BigDecimal dec(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(1) : v;
    }
}

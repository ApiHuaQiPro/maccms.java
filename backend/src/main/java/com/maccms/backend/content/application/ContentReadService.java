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
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodSummaryRow;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 不记录条件值。不带新条件的请求不经过这两项，SQL 与 1.0.0 相同。
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

    /** 连接池大小取 Hikari 的同一配置（application.yml 中 MACCMS_DB_POOL_SIZE，默认 10），用于校验并发上限。 */
    @Autowired
    public ContentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper,
                              ContentProperties properties,
                              @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        this.typeMapper = typeMapper;
        this.groupMapper = groupMapper;
        this.vodMapper = vodMapper;
        this.properties = properties;
        this.search = SearchSettings.from(properties.search(), poolSize);
        this.searchPermits = new Semaphore(search.maxConcurrent());
    }

    /** 测试与无 Spring 场景：连接池按默认 10。 */
    public ContentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper,
                              ContentProperties properties) {
        this(typeMapper, groupMapper, vodMapper, properties, 10);
    }

    /** 是否接受包含匹配参数 keyword、classes（{@code maccms.content.search.enabled}）。 */
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
        if (!filter.usesContainsMatch()) {
            return read(() -> doListVideos(query));
        }
        if (!search.enabled()) {
            throw ApiException.invalidParameter(filter.keyword() != null ? "keyword" : "classes",
                    VideoFilter.SEARCH_DISABLED);
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

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }

    private PageResponse<VideoSummaryDto> doListVideos(VideoListQuery query) {
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
        VodFilterCriteria criteria = criteria(query.filter());
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

    /** 不带新条件时返回 null，使用与 1.0.0 相同的语句。 */
    private static VodFilterCriteria criteria(VideoFilter f) {
        if (f.isEmpty()) {
            return null;
        }
        return new VodFilterCriteria(f.level(), f.year(), f.area(), f.lang(), f.classesPattern(), f.keywordPatterns());
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

    private static List<CategoryDto> toTree(CategoryCatalog catalog, List<TypeRow> rows) {
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

    private static VideoSummaryDto toSummary(VodSummaryRow r, CategoryCatalog catalog) {
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

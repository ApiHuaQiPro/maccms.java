package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;

/**
 * GET /api/v1/videos 的已校验参数。取值范围是契约的一部分，见 docs/contracts/content-read.openapi.yaml。
 * 空字符串参数按"未传"处理。
 *
 * <p>紧凑构造器与 {@link #parse} 使用相同的取值范围和错误类型，其他模块直接构造时也不能越过页大小等上限；
 * {@code categoryId == null} 表示不按分类筛选。{@code filter} 是契约 1.1.0 的搜索/筛选条件（{@link VideoFilter}），
 * {@code null} 视为 {@link VideoFilter#NONE}；5 参数构造器与 {@link #parse} 不带新条件，行为与 1.0.0 相同。
 *
 * <p>{@code random}（契约 1.3.0 的 {@code sort=rnd}，旧 Vod::listCacheData 的 by=rnd，application/common/model/Vod.php:500-537）：
 * 按旧版的随机页/随机 ID 段算法取一页，此时 {@code sort} 固定为 {@link VideoSort#HITS_WEEK}、{@code descending} 为
 * {@code true}（旧版改写为 hits_week desc，与请求的 order 无关）。规则见 {@link ContentReadService} 与
 * docs/architecture/14-video-list-filter-completion.md 第 4 节。
 *
 * <p>{@code legacy}（B05）：旧模板标签专属条件与 SSR 的 tid（{@link VideoLegacyConditions}），只由其他模块构造，公开 API
 * 一律为 {@link VideoLegacyConditions#NONE}（{@link #parse}）。与 {@code categoryId}、{@code filter} 的覆盖关系见该类；
 * 构造时拒绝旧版中同一个变量的两种写法（{@code categoryId} 与 {@code types}、{@code filter.level} 与 {@code levels}）。
 * 旧 {@code start} 只改变 {@link #offset()}。
 */
public record VideoListQuery(Integer categoryId, VideoSort sort, boolean descending, int page, int pageSize,
                             VideoFilter filter, boolean random, VideoLegacyConditions legacy) {

    /** 随机排序的对外取值（不在 {@link VideoSort} 中：它不是一个排序列）。 */
    public static final String RANDOM_SORT = "rnd";

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 1000;
    public static final int MAX_CATEGORY_ID = 65535;

    public VideoListQuery {
        if (categoryId != null) {
            checkRange("categoryId", categoryId, 1, MAX_CATEGORY_ID);
        }
        if (sort == null) {
            throw ApiException.invalidParameter("sort", "不支持的排序字段");
        }
        checkRange("page", page, 1, MAX_PAGE);
        checkRange("pageSize", pageSize, 1, MAX_PAGE_SIZE);
        if (filter == null) {
            filter = VideoFilter.NONE;
        }
        if (random && (sort != VideoSort.HITS_WEEK || !descending)) {
            throw new IllegalArgumentException("随机排序固定按 hits_week 倒序取页");
        }
        if (legacy == null) {
            legacy = VideoLegacyConditions.NONE;
        }
        if (categoryId != null && legacy.types() != null) {
            throw new IllegalArgumentException("categoryId 与 types 是旧版同一个 type 变量，只能设置一个");
        }
        if (filter.level() != null && legacy.levels() != null) {
            throw new IllegalArgumentException("level 与 levels 是旧版同一个 level 变量，只能设置一个");
        }
    }

    public VideoListQuery(Integer categoryId, VideoSort sort, boolean descending, int page, int pageSize,
                          VideoFilter filter, boolean random) {
        this(categoryId, sort, descending, page, pageSize, filter, random, VideoLegacyConditions.NONE);
    }

    public VideoListQuery(Integer categoryId, VideoSort sort, boolean descending, int page, int pageSize) {
        this(categoryId, sort, descending, page, pageSize, VideoFilter.NONE, false);
    }

    public VideoListQuery(Integer categoryId, VideoSort sort, boolean descending, int page, int pageSize,
                          VideoFilter filter) {
        this(categoryId, sort, descending, page, pageSize, filter, false);
    }

    public static VideoListQuery parse(String categoryId, String sort, String order, String page, String pageSize) {
        Integer category = blank(categoryId) ? null : parseInt("categoryId", categoryId, 1, MAX_CATEGORY_ID);
        boolean random = RANDOM_SORT.equals(sort);
        VideoSort videoSort = blank(sort) ? VideoSort.TIME : random ? VideoSort.HITS_WEEK : VideoSort.fromApiValue(sort)
                .orElseThrow(() -> ApiException.invalidParameter("sort", "不支持的排序字段"));
        boolean descending;
        if (blank(order) || "desc".equals(order)) {
            descending = true;
        } else if ("asc".equals(order)) {
            descending = false;
        } else {
            throw ApiException.invalidParameter("order", "只能是 asc 或 desc");
        }
        int p = blank(page) ? 1 : parseInt("page", page, 1, MAX_PAGE);
        int size = blank(pageSize) ? DEFAULT_PAGE_SIZE : parseInt("pageSize", pageSize, 1, MAX_PAGE_SIZE);
        // rnd：order 照常校验，但不影响结果（旧版固定 hits_week desc）
        return new VideoListQuery(category, videoSort, random || descending, p, size, VideoFilter.NONE, random,
                VideoLegacyConditions.NONE);
    }

    /** 同一组分类/排序/分页参数，换成给定的搜索/筛选条件。 */
    public VideoListQuery withFilter(VideoFilter newFilter) {
        return new VideoListQuery(categoryId, sort, descending, page, pageSize, newFilter, random, legacy);
    }

    /** 同一组参数，换成给定的旧标签条件（B05）。 */
    public VideoListQuery withLegacy(VideoLegacyConditions newLegacy) {
        return new VideoListQuery(categoryId, sort, descending, page, pageSize, filter, random, newLegacy);
    }

    /** 分页偏移：(page − 1) × pageSize，另加旧 start 的偏移（{@link VideoLegacyConditions#startOffset()}）。 */
    public long offset() {
        return offsetOf(page);
    }

    /** 给定页码的分页偏移（随机排序取随机页时使用）。 */
    long offsetOf(long anyPage) {
        return (anyPage - 1) * pageSize + legacy.startOffset();
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty();
    }

    static int parseInt(String field, String raw, int min, int max) {
        if (raw.length() > 10 || !raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw ApiException.invalidParameter(field, "必须是 " + min + " 到 " + max + " 之间的整数");
        }
        long v = Long.parseLong(raw);
        checkRange(field, v, min, max);
        return (int) v;
    }

    private static void checkRange(String field, long value, int min, int max) {
        if (value < min || value > max) {
            throw ApiException.invalidParameter(field, "必须是 " + min + " 到 " + max + " 之间的整数");
        }
    }
}

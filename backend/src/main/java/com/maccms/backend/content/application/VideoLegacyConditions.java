package com.maccms.backend.content.application;

import java.util.List;
import java.util.Objects;

/**
 * 旧模板标签专属的视频列表条件，以及 SSR 搜索页的 {@code tid}（B05，docs/architecture/15-b05-readonly-pages.md 第 2 节）。
 * 旧版这些条件来自 {@code {maccms:vod …}} 标签属性（{@code mac_param_url} 不读取，URL 到达不了），{@code tid} 则是 URL 可达但
 * 公开 API 不新增的条件（队长裁定 Q2）。只供其他模块（site）经 {@link ContentReadService#listVideos} 使用：公开 API 从不构造
 * （{@link VideoListQuery#parse} 一律为 {@link #NONE}），所以不是前台 URL 输入。字段为 {@code null} 表示未设置。
 *
 * <p>与旧 {@code Vod::listCacheData}（application/common/model/Vod.php:108-567）的对应与覆盖关系：
 * <ul>
 * <li>{@code types}、{@code typeNot}、{@link VideoListQuery#categoryId()} 在旧版写入同一个 {@code $where['type_id']}：
 * {@code typeNot} 设置时覆盖另外两个（:352-354 在 :337-351 之后）；{@code types} 与 {@code categoryId} 同是 {@code $type}
 * 变量，只能设置一个。三者都只在可见分类内生效（1.0.0 起已接受的可见性限制）。</li>
 * <li>{@code notIds}、数字形式的 {@code rel} 与 {@link VideoFilter#ids()} 写入同一个 {@code $where['vod_id']}（:273-284）：
 * 数字 rel 覆盖 not，二者覆盖 ids。非数字的 rel 是 {@code vod_rel_vod} 上的包含匹配，不覆盖。</li>
 * <li>{@code levels}（旧 level 多值、{@code all}）与 {@link VideoFilter#level()} 同是 {@code $level}，只能设置一个。</li>
 * <li>{@code weekday}、非数字的 {@code rel} 是包含匹配：与 keyword 等共用搜索开关、并发许可与执行时限（内部能力不绕过成本护栏）。</li>
 * <li>{@code start}（:157-159）只改变分页偏移：实际偏移 = 每页条数 ×（页码 − 1）+（start &gt; 1 ? start − 1 : start），total 不变。</li>
 * </ul>
 * 旧标签属性 {@code half}（:565）只供模板把一页拆成两半显示，不影响查询，这里没有对应字段。
 *
 * @param tid           旧 tid（intval 后不为 0）：{@code type_id = tid OR type_id_1 = tid}（:180-182、:356-358），负数照常筛选（无结果）
 * @param types         旧 type：每项本身与它的直接子分类（:337-351，type_id 或 type_pid 命中；0 即全部顶级分类）；空列表不命中任何分类
 * @param typeNot       旧 typenot：type_id 不在其中（覆盖 types 与 categoryId）
 * @param notIds        旧 not：vod_id 不在其中
 * @param relIds        旧 rel 全为数字时：vod_id 在其中（覆盖 notIds 与 ids）
 * @param rel           旧 rel 不全为数字时：vod_rel_vod 包含任一项（{@code mac_like_arr}，保留空项）
 * @param levels        旧 level 多值或 all（all 为 1..9）：vod_level 在其中（各项为 intval 结果，不限范围，范围外不命中）
 * @param weekday       旧 weekday：vod_weekday 包含任一项（{@code mac_like_arr}）
 * @param tvs           旧 tv：vod_tv 在其中（逗号拆分，保留空项）
 * @param addedAfter    旧 timeadd：vod_time_add 大于该时间点（Unix 秒；旧版为 {@code intval(strtotime())}，由调用方换算）
 * @param hitsTimeAfter 旧 timehits：vod_time_hits 大于该时间点
 * @param updatedAfter  旧 time：vod_time 大于该时间点
 * @param hitsMonth     旧 hitsmonth：vod_hits_month 范围
 * @param hitsWeek      旧 hitsweek：vod_hits_week 范围
 * @param hitsDay       旧 hitsday：vod_hits_day 范围
 * @param hits          旧 hits：vod_hits 范围
 * @param isEnd         旧 isend：0 或 1（两者都是有效的显式筛选）
 * @param plot          旧 plot：0 或 1
 * @param start         旧 start 属性（{@code intval(abs())}，&ge; 0）
 */
public record VideoLegacyConditions(Long tid, List<Integer> types, List<Integer> typeNot, List<Long> notIds,
                                    List<Long> relIds, String rel, List<Integer> levels, String weekday,
                                    List<String> tvs, Long addedAfter, Long hitsTimeAfter, Long updatedAfter,
                                    LegacyRange hitsMonth, LegacyRange hitsWeek, LegacyRange hitsDay, LegacyRange hits,
                                    Integer isEnd, Integer plot, int start) {

    public static final VideoLegacyConditions NONE = builder().build();

    /** 列表条件（types、typeNot、notIds、relIds、levels、tvs）的项数上限：新版的请求大小上限，旧版不限。 */
    public static final int MAX_ITEMS = 100;
    /** rel、weekday 原始值的码点上限（与 1.3.0 其他逗号多值参数相同）。 */
    public static final int MAX_TEXT_LENGTH = VideoFilter.MAX_LIST_LENGTH;
    /** start 的上限：分页偏移不超过页码上限 × 页大小上限。 */
    public static final int MAX_START = VideoListQuery.MAX_PAGE * VideoListQuery.MAX_PAGE_SIZE;

    public VideoLegacyConditions {
        if (tid != null && tid == 0) {
            throw new IllegalArgumentException("tid 为 0 时表示不筛选，应不设置");
        }
        // types 可以是空列表：旧版各项都不是数字时 $type 为空数组，条件为 type_id IN ('')，不命中任何分类（Vod.php:341-350）
        types = types == null ? null : types.isEmpty() ? List.of() : list("types", types);
        typeNot = list("typeNot", typeNot);
        notIds = list("notIds", notIds);
        relIds = list("relIds", relIds);
        levels = list("levels", levels);
        tvs = list("tvs", tvs);
        if (relIds != null && rel != null) {
            throw new IllegalArgumentException("rel 只能是数字形式或文本形式之一");
        }
        rel = text("rel", rel);
        weekday = text("weekday", weekday);
        if (tvs != null) {
            for (String v : tvs) {
                text("tvs", v.isEmpty() ? "x" : v);
            }
        }
        if ((isEnd != null && isEnd != 0 && isEnd != 1) || (plot != null && plot != 0 && plot != 1)) {
            throw new IllegalArgumentException("isEnd / plot 只能是 0 或 1");
        }
        if (start < 0 || start > MAX_START) {
            throw new IllegalArgumentException("start 越界");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 没有任何条件且 start 为 0。 */
    public boolean isEmpty() {
        return equals(NONE);
    }

    /** 带包含匹配条件（受搜索开关与并发许可约束）：weekday、文本形式的 rel。 */
    public boolean usesContainsMatch() {
        return weekday != null || rel != null;
    }

    /** 第一个设置了的包含匹配条件名，没有时为 {@code null}。 */
    public String firstContainsMatchParameter() {
        return rel != null ? "rel" : weekday != null ? "weekday" : null;
    }

    /** 旧 start 换算后的分页偏移增量（Vod.php:157-159：大于 1 时减 1）。 */
    public int startOffset() {
        return start > 1 ? start - 1 : start;
    }

    /** rel 各项的 LIKE 模式（空项为 {@code %%}，命中全部，与旧 mac_like_arr 相同）。 */
    public List<String> relPatterns() {
        return patterns(rel);
    }

    /** weekday 各项的 LIKE 模式。 */
    public List<String> weekdayPatterns() {
        return patterns(weekday);
    }

    private static List<String> patterns(String v) {
        if (v == null) {
            return null;
        }
        return VideoFilter.items(v).stream().map(VideoFilter::contains).toList();
    }

    private static <T> List<T> list(String field, List<T> v) {
        if (v == null) {
            return null;
        }
        if (v.isEmpty() || v.size() > MAX_ITEMS || v.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " 须为 1 到 " + MAX_ITEMS + " 项");
        }
        return List.copyOf(v);
    }

    private static String text(String field, String v) {
        if (v == null) {
            return null;
        }
        if (v.isEmpty() || v.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " 长度不合法");
        }
        for (int i = 0; i < v.length(); i++) {
            if (VideoFilter.forbidden(v.charAt(i))) {
                throw new IllegalArgumentException(field + " 含不允许的字符");
            }
        }
        return v;
    }

    /** 逐项设置；未设置的项为 {@code null}。 */
    public static final class Builder {
        private Long tid;
        private List<Integer> types;
        private List<Integer> typeNot;
        private List<Long> notIds;
        private List<Long> relIds;
        private String rel;
        private List<Integer> levels;
        private String weekday;
        private List<String> tvs;
        private Long addedAfter;
        private Long hitsTimeAfter;
        private Long updatedAfter;
        private LegacyRange hitsMonth;
        private LegacyRange hitsWeek;
        private LegacyRange hitsDay;
        private LegacyRange hits;
        private Integer isEnd;
        private Integer plot;
        private int start;

        private Builder() {
        }

        public Builder tid(Long v) {
            tid = v;
            return this;
        }

        public Builder types(List<Integer> v) {
            types = v;
            return this;
        }

        public Builder typeNot(List<Integer> v) {
            typeNot = v;
            return this;
        }

        public Builder notIds(List<Long> v) {
            notIds = v;
            return this;
        }

        public Builder relIds(List<Long> v) {
            relIds = v;
            return this;
        }

        public Builder rel(String v) {
            rel = v;
            return this;
        }

        public Builder levels(List<Integer> v) {
            levels = v;
            return this;
        }

        public Builder weekday(String v) {
            weekday = v;
            return this;
        }

        public Builder tvs(List<String> v) {
            tvs = v;
            return this;
        }

        public Builder addedAfter(Long v) {
            addedAfter = v;
            return this;
        }

        public Builder hitsTimeAfter(Long v) {
            hitsTimeAfter = v;
            return this;
        }

        public Builder updatedAfter(Long v) {
            updatedAfter = v;
            return this;
        }

        public Builder hitsMonth(LegacyRange v) {
            hitsMonth = v;
            return this;
        }

        public Builder hitsWeek(LegacyRange v) {
            hitsWeek = v;
            return this;
        }

        public Builder hitsDay(LegacyRange v) {
            hitsDay = v;
            return this;
        }

        public Builder hits(LegacyRange v) {
            hits = v;
            return this;
        }

        public Builder isEnd(Integer v) {
            isEnd = v;
            return this;
        }

        public Builder plot(Integer v) {
            plot = v;
            return this;
        }

        public Builder start(int v) {
            start = v;
            return this;
        }

        public VideoLegacyConditions build() {
            return new VideoLegacyConditions(tid, types, typeNot, notIds, relIds, rel, levels, weekday, tvs, addedAfter,
                    hitsTimeAfter, updatedAfter, hitsMonth, hitsWeek, hitsDay, hits, isEnd, plot, start);
        }
    }
}

package com.maccms.backend.content.infrastructure;

import java.util.List;

/**
 * VodMapper.xml 中 {@code legacyConditions} 片段的绑定值（B05：旧模板标签专属条件与 SSR 的 tid，
 * docs/architecture/15-b05-readonly-pages.md 第 2 节），由 content.application 用已校验的条件构造。列名与运算符固定写在 XML 中，
 * 值只经 {@code #{}} 绑定；字段为 {@code null} 表示该条件不参与，列表字段非 {@code null} 时至少一项。
 *
 * @param tid             {@code (type_id = ? OR type_id_1 = ?)}（application/common/model/Vod.php:180-182、:356-358）
 * @param idsNotIn        {@code vod_id NOT IN}（旧 not，:278-280）
 * @param relPatterns     {@code vod_rel_vod} 的 LIKE 模式，项之间 OR（旧 rel 非数字时，:281-288）
 * @param levels          {@code vod_level IN}（旧 level 逗号多值或 all，:285-290）
 * @param weekdayPatterns {@code vod_weekday} 的 LIKE 模式，项之间 OR（旧 weekday，:311-314）
 * @param tvs             {@code vod_tv IN}（旧 tv，:315-317）
 * @param addedAfter      {@code vod_time_add > ?}（旧 timeadd，:318-321）
 * @param hitsTimeAfter   {@code vod_time_hits > ?}（旧 timehits，:322-325）
 * @param updatedAfter    {@code vod_time > ?}（旧 time，:326-329）
 * @param hitsMonth       {@code vod_hits_month} 范围（旧 hitsmonth，:363-370）
 * @param hitsWeek        {@code vod_hits_week} 范围（旧 hitsweek，:371-378）
 * @param hitsDay         {@code vod_hits_day} 范围（旧 hitsday，:379-386）
 * @param hits            {@code vod_hits} 范围（旧 hits，:387-394）
 * @param isEnd           {@code vod_isend = ?}（旧 isend，:396-398）
 * @param plot            {@code vod_plot = ?}（旧 plot，:484-486）
 */
public record VodLegacyCriteria(Long tid, List<Long> idsNotIn, List<String> relPatterns, List<Integer> levels,
                                List<String> weekdayPatterns, List<String> tvs, Long addedAfter, Long hitsTimeAfter,
                                Long updatedAfter, VodRangeCriteria hitsMonth, VodRangeCriteria hitsWeek,
                                VodRangeCriteria hitsDay, VodRangeCriteria hits, Integer isEnd, Integer plot) {

    public VodLegacyCriteria {
        idsNotIn = nonEmpty("idsNotIn", idsNotIn);
        relPatterns = nonEmpty("relPatterns", relPatterns);
        levels = nonEmpty("levels", levels);
        weekdayPatterns = nonEmpty("weekdayPatterns", weekdayPatterns);
        tvs = nonEmpty("tvs", tvs);
        if ((isEnd != null && isEnd != 0 && isEnd != 1) || (plot != null && plot != 0 && plot != 1)) {
            throw new IllegalArgumentException("isEnd / plot 只能是 0 或 1");
        }
        if (isEmpty(tid, idsNotIn, relPatterns, levels, weekdayPatterns, tvs, addedAfter, hitsTimeAfter, updatedAfter,
                hitsMonth, hitsWeek, hitsDay, hits, isEnd, plot)) {
            throw new IllegalArgumentException("没有任何条件时不应构造");
        }
    }

    private static boolean isEmpty(Object... fields) {
        for (Object f : fields) {
            if (f != null) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> nonEmpty(String field, List<T> v) {
        if (v == null) {
            return null;
        }
        if (v.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空列表");
        }
        return List.copyOf(v);
    }
}

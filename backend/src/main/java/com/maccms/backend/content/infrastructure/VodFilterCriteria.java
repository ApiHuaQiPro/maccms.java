package com.maccms.backend.content.infrastructure;

import java.util.List;

/**
 * VodMapper.xml 中 {@code matchingConditions} 片段的绑定值，由 content.application 用已校验的条件构造。
 * 只作为 {@code #{}} 参数绑定，列名固定写在 XML 中；字段为 {@code null} 表示该条件不参与，列表字段非 {@code null} 时至少一项。
 *
 * @param level            vod_level 精确匹配
 * @param year             vod_year 精确匹配（字符串比较，单值）
 * @param area             vod_area 精确匹配
 * @param lang             vod_lang 精确匹配
 * @param classesPattern   vod_class 的 LIKE 模式（已按 ESCAPE '!' 转义并加首尾 %）
 * @param keywordPatterns  vod_name/vod_sub/vod_en 的 LIKE 模式（1 或 2 项，项之间 OR）
 * @param years            vod_year IN（年份区间展开后的多项；单个年份用 {@code year}，保持 1.1.0 的 SQL 文本）
 * @param letters          vod_letter IN
 * @param states           vod_state IN
 * @param versions         vod_version IN
 * @param actorPatterns    vod_actor 的 LIKE 模式（项之间 OR）
 * @param directorPatterns vod_director 的 LIKE 模式（项之间 OR）
 * @param tagPatterns      vod_tag 的 LIKE 模式（项之间 OR）
 * @param namePatterns     vod_name 的 LIKE 模式（项之间 OR）
 * @param ids              vod_id IN（ids 参数）
 * @param candidateIds     vod_id IN（随机排序的候选 ID 段，与 ids 同时存在时两者都要满足，与旧版相同）
 * @param areas            vod_area IN（area 逗号多值的各项；单值用 {@code area}，保持 1.1.0 的 SQL 文本）
 * @param langs            vod_lang IN（lang 逗号多值的各项；单值用 {@code lang}）
 * @param classesPatterns  vod_class 的 LIKE 模式（classes 逗号多值的各项，项之间 OR；单值用 {@code classesPattern}）
 * @param legacy           旧模板标签专属条件与 SSR 的 tid（B05，{@link VodLegacyCriteria}），追加在以上条件之后；{@code null} 时 SQL 文本不变
 */
public record VodFilterCriteria(Integer level, String year, String area, String lang, String classesPattern,
                                List<String> keywordPatterns, List<String> years, List<String> letters,
                                List<String> states, List<String> versions, List<String> actorPatterns,
                                List<String> directorPatterns, List<String> tagPatterns, List<String> namePatterns,
                                List<Long> ids, List<Long> candidateIds, List<String> areas, List<String> langs,
                                List<String> classesPatterns, VodLegacyCriteria legacy) {

    public VodFilterCriteria {
        if (keywordPatterns != null) {
            keywordPatterns = List.copyOf(keywordPatterns);
            if (keywordPatterns.isEmpty() || keywordPatterns.size() > 2) {
                throw new IllegalArgumentException("keywordPatterns 只能有 1 或 2 项");
            }
        }
        years = nonEmpty("years", years);
        letters = nonEmpty("letters", letters);
        states = nonEmpty("states", states);
        versions = nonEmpty("versions", versions);
        actorPatterns = nonEmpty("actorPatterns", actorPatterns);
        directorPatterns = nonEmpty("directorPatterns", directorPatterns);
        tagPatterns = nonEmpty("tagPatterns", tagPatterns);
        namePatterns = nonEmpty("namePatterns", namePatterns);
        ids = nonEmpty("ids", ids);
        candidateIds = nonEmpty("candidateIds", candidateIds);
        areas = nonEmpty("areas", areas);
        langs = nonEmpty("langs", langs);
        classesPatterns = nonEmpty("classesPatterns", classesPatterns);
        if (year != null && years != null) {
            throw new IllegalArgumentException("year 与 years 只能设置一个");
        }
        if ((area != null && areas != null) || (lang != null && langs != null)
                || (classesPattern != null && classesPatterns != null)) {
            throw new IllegalArgumentException("单值与多值形式只能设置一个");
        }
        if (level == null && year == null && area == null && lang == null && classesPattern == null
                && keywordPatterns == null && years == null && letters == null && states == null && versions == null
                && actorPatterns == null && directorPatterns == null && tagPatterns == null && namePatterns == null
                && ids == null && candidateIds == null && areas == null && langs == null && classesPatterns == null
                && legacy == null) {
            throw new IllegalArgumentException("没有任何条件时应使用不带条件的查询");
        }
    }

    /** 不含 B05 旧标签条件（{@code legacy} 为 {@code null}）。 */
    public VodFilterCriteria(Integer level, String year, String area, String lang, String classesPattern,
                             List<String> keywordPatterns, List<String> years, List<String> letters,
                             List<String> states, List<String> versions, List<String> actorPatterns,
                             List<String> directorPatterns, List<String> tagPatterns, List<String> namePatterns,
                             List<Long> ids, List<Long> candidateIds, List<String> areas, List<String> langs,
                             List<String> classesPatterns) {
        this(level, year, area, lang, classesPattern, keywordPatterns, years, letters, states, versions, actorPatterns,
                directorPatterns, tagPatterns, namePatterns, ids, candidateIds, areas, langs, classesPatterns, null);
    }

    /** 契约 1.1.0 的 6 个条件。 */
    public VodFilterCriteria(Integer level, String year, String area, String lang, String classesPattern,
                             List<String> keywordPatterns) {
        this(level, year, area, lang, classesPattern, keywordPatterns, null, null, null, null, null, null, null, null,
                null, null);
    }

    /** 不含 area/lang/classes 多值形式的条件（契约 1.3.0 中这三个参数只有单值时）。 */
    public VodFilterCriteria(Integer level, String year, String area, String lang, String classesPattern,
                             List<String> keywordPatterns, List<String> years, List<String> letters,
                             List<String> states, List<String> versions, List<String> actorPatterns,
                             List<String> directorPatterns, List<String> tagPatterns, List<String> namePatterns,
                             List<Long> ids, List<Long> candidateIds) {
        this(level, year, area, lang, classesPattern, keywordPatterns, years, letters, states, versions, actorPatterns,
                directorPatterns, tagPatterns, namePatterns, ids, candidateIds, null, null, null);
    }

    /** 只有随机排序候选 ID 段的条件。 */
    public static VodFilterCriteria candidatesOnly(List<Long> candidateIds) {
        return new VodFilterCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, candidateIds);
    }

    /** 同一组条件另加随机排序候选 ID 段。 */
    public VodFilterCriteria withCandidates(List<Long> newCandidateIds) {
        return new VodFilterCriteria(level, year, area, lang, classesPattern, keywordPatterns, years, letters, states,
                versions, actorPatterns, directorPatterns, tagPatterns, namePatterns, ids, newCandidateIds, areas,
                langs, classesPatterns, legacy);
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

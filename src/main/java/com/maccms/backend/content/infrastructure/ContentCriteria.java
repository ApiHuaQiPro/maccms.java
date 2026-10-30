package com.maccms.backend.content.infrastructure;

import java.util.List;

/**
 * 文章/专题/演员/角色/网址/友链列表的精确筛选条件（契约 1.2.0），由 content.application 从已校验的参数构造；
 * {@code null} 表示该条件未设置。每个 Mapper 只引用本表有的条件，列名写死在 XML 中，值只经 {@code #{}} 绑定。
 *
 * <p>B05 的 SSR 筛选/搜索页另有旧 URL 条件（{@code keywordPatterns} 起，只由 site 经
 * {@code content.application.LibraryMatch} 构造，公开 API 从不设置；不设置时 SQL 文本不变）。LIKE 模式已按 {@code ESCAPE '!'}
 * 转义并加首尾 %（与视频相同，VideoFilter）。逗号多值沿用旧 {@code explode(',')}：保留空项，空项匹配该列为空串的行。
 *
 * @param letters         首字母（旧 letter：契约 1.2.0 为单个字符或 "0-9" 展开；SSR 为 explode 的各项或 0?9 展开）
 * @param sex             演员性别（精确，SSR 不限制取值形式，旧 Actor.php 的 {@code eq}）
 * @param videoId         角色所属视频 role_rid（契约 1.2.0 为 1..4294967295；SSR 为旧 rid 的 intval，负数照常比较）
 * @param linkType        友链类型 0 文字、1 图片（旧 type=font/pic）
 * @param keywordPatterns 旧 wd 的 LIKE 模式（各 Mapper 的名称列，项之间 OR）
 * @param namePatterns    旧 name 的 LIKE 模式（文章 art_name，mac_like_arr，项之间 OR）
 * @param names           旧 name 的精确值（网址 website_name IN，Website.php:369-371）
 * @param tagPatterns     旧 tag 的 LIKE 模式（项之间 OR）
 * @param classPatterns   旧 class 的 LIKE 模式（文章 art_class、专题 topic_type、网址 website_class，项之间 OR）
 * @param ids             旧 ids（主键 IN；空项为 0，命中不了任何行）
 * @param tid             旧 tid（文章 {@code type_id = tid OR type_id_1 = tid}，Art.php:173-174、:291-292）
 * @param legacyLevel     旧 SSR level（mac_param_url 的 intval，非 0 的任意整数，{@code *_level = ?}；越界与负数照常比较、无结果）
 * @param areas           旧 area 的各项（演员 actor_area、网址 website_area IN）
 * @param langs           旧 lang 的各项（网址 website_lang IN）
 * @param starSigns       旧 starsign 的各项（actor_starsign IN）
 * @param bloods          旧 blood 的各项（actor_blood IN）
 * @param nameOrder       网址 by=in 的排序名单（{@code FIND_IN_SET(website_name, ?)}，Website.php:288-291；只与
 *                        {@link SortColumn#NAME_ORDER} 一起使用）
 */
public record ContentCriteria(Integer level, List<String> letters, String sex, String area, String lang,
                              String starSign, String blood, Long videoId, Integer linkType,
                              List<String> keywordPatterns, List<String> namePatterns, List<String> names,
                              List<String> tagPatterns, List<String> classPatterns, List<Long> ids, Long tid,
                              Long legacyLevel, List<String> areas, List<String> langs, List<String> starSigns,
                              List<String> bloods, String nameOrder) {

    public static final ContentCriteria NONE = new ContentCriteria(null, null, null, null, null, null, null, null, null);

    public ContentCriteria {
        letters = copy(letters);
        keywordPatterns = copy(keywordPatterns);
        namePatterns = copy(namePatterns);
        names = copy(names);
        tagPatterns = copy(tagPatterns);
        classPatterns = copy(classPatterns);
        ids = copy(ids);
        areas = copy(areas);
        langs = copy(langs);
        starSigns = copy(starSigns);
        bloods = copy(bloods);
        if (level != null && legacyLevel != null) {
            throw new IllegalArgumentException("level 与 legacyLevel 只能设置一个");
        }
    }

    /** 契约 1.2.0 的精确筛选条件（没有 B05 的 SSR 条件）。 */
    public ContentCriteria(Integer level, List<String> letters, String sex, String area, String lang,
                           String starSign, String blood, Long videoId, Integer linkType) {
        this(level, letters, sex, area, lang, starSign, blood, videoId, linkType, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static <T> List<T> copy(List<T> v) {
        if (v == null) {
            return null;
        }
        if (v.isEmpty()) {
            throw new IllegalArgumentException("条件列表不能为空");
        }
        return List.copyOf(v);
    }
}

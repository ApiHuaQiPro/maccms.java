package com.maccms.backend.content.infrastructure;

import java.util.List;

/**
 * 文章/专题/演员/角色/网址/友链列表的精确筛选条件（契约 1.2.0），由 content.application 从已校验的参数构造；
 * {@code null} 表示该条件未设置。每个 Mapper 只引用本表有的条件，列名写死在 XML 中，值只经 {@code #{}} 绑定。
 *
 * <p>B05 的 SSR 筛选/搜索页另有包含匹配与 ID 条件（{@code keywordPatterns} 起，只由 site 经
 * {@code content.application.LibraryMatch} 构造，公开 API 从不设置；不设置时 SQL 文本不变）。LIKE 模式已按 {@code ESCAPE '!'}
 * 转义并加首尾 %（与视频相同，VideoFilter）。
 *
 * @param letters         首字母（旧 letter：单个字符，或 "0-9" 展开为 10 个数字）
 * @param videoId         角色所属视频 role_rid（旧 rid）
 * @param linkType        友链类型 0 文字、1 图片（旧 type=font/pic）
 * @param keywordPatterns 旧 wd 的 LIKE 模式（各 Mapper 的名称列，项之间 OR）
 * @param namePatterns    旧 name 的 LIKE 模式（文章 art_name，mac_like_arr，项之间 OR）
 * @param names           旧 name 的精确值（网址 website_name IN，Website.php:369-371）
 * @param tagPatterns     旧 tag 的 LIKE 模式（项之间 OR）
 * @param classPatterns   旧 class 的 LIKE 模式（文章 art_class、专题 topic_type、网址 website_class，项之间 OR）
 * @param ids             旧 ids（主键 IN；空项为 0，命中不了任何行）
 * @param tid             旧 tid（文章 {@code type_id = tid OR type_id_1 = tid}，Art.php:173-174、:291-292）
 */
public record ContentCriteria(Integer level, List<String> letters, String sex, String area, String lang,
                              String starSign, String blood, Long videoId, Integer linkType,
                              List<String> keywordPatterns, List<String> namePatterns, List<String> names,
                              List<String> tagPatterns, List<String> classPatterns, List<Long> ids, Long tid) {

    public static final ContentCriteria NONE = new ContentCriteria(null, null, null, null, null, null, null, null, null);

    public ContentCriteria {
        keywordPatterns = copy(keywordPatterns);
        namePatterns = copy(namePatterns);
        names = copy(names);
        tagPatterns = copy(tagPatterns);
        classPatterns = copy(classPatterns);
        ids = copy(ids);
    }

    /** 契约 1.2.0 的精确筛选条件（没有 B05 的包含匹配与 ID 条件）。 */
    public ContentCriteria(Integer level, List<String> letters, String sex, String area, String lang,
                           String starSign, String blood, Long videoId, Integer linkType) {
        this(level, letters, sex, area, lang, starSign, blood, videoId, linkType, null, null, null, null, null, null,
                null);
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

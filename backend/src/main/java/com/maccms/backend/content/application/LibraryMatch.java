package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文章、专题、演员、角色、网址的 SSR 筛选/搜索页条件（B05，docs/architecture/15-b05-readonly-pages.md 第 7.2 节，队长裁定
 * D-B05-5 与独立验收返工第 2 项）：旧 {@code listCacheData} 在 {@code paging=yes} 时从 URL 读取（经 {@code mac_param_url}，
 * application/common.php:1560-1606）的全部条件。只由 site 构造，公开 API 不新增参数（契约 1.2.0 的 {@link ContentFilter} 不变）。
 *
 * <p>取值规则逐键按旧调用链（不套用视频或契约 1.2.0 的单值规则）：
 * <ul>
 *   <li>{@code level}、{@code rid}、{@code tid}、专题的 {@code id}：mac_param_url / 模型中的 {@code intval}，非 0 的任意整数
 *       （负数、越界照常比较，无结果）；所以 URL 的 {@code level} 只有单值（{@code 1,2} 为 1、{@code all} 为 0 即未设置，由 site
 *       归一）。</li>
 *   <li>{@code letter}：explode(',') 的各项（保留空项），{@code 0?9} 展开为 10 个数字（{@link VideoFilter#letters()}）。</li>
 *   <li>{@code area}、{@code lang}、{@code starsign}、{@code blood}：explode(',') 的各项 IN（保留空项，空项匹配该列为空的行）；
 *       {@code sex}：精确。</li>
 *   <li>{@code wd}、{@code name}、{@code tag}、{@code class}、{@code ids}：与视频相同的文本规则与 LIKE 模式（{@link VideoFilter}，
 *       {@code ESCAPE '!'}，含 htmlspecialchars 变体）；网址的 name 为精确 IN。</li>
 * </ul>
 * 文本值都经 {@link VideoFilter#text} 的同一套规则（禁用字符、首尾空白、码点上限，列表项数不超过 {@link #MAX_ITEMS}）。
 * 包含匹配条件（wd/name/tag/class，网址的 name 为精确 IN 但同样按搜索计）与视频列表共用开关、并发许可与执行时限
 * （{@link ContentReadService#guardedRead}），关闭时 400。各类型接受的条件见 {@link #supported(ContentKind)}。
 *
 * @param keyword  旧 wd
 * @param name     旧 name（文章 LIKE，网址精确 IN）
 * @param tag      旧 tag
 * @param classes  旧 class
 * @param ids      旧 ids（{@code all} 为不筛选）
 * @param tid      旧 tid（只有文章：{@code type_id = tid OR type_id_1 = tid}）
 * @param level    旧 level 的 intval（非 0）
 * @param letter   旧 letter
 * @param sex      旧 sex（演员）
 * @param area     旧 area（演员、网址）
 * @param lang     旧 lang（网址）
 * @param starSign 旧 starsign（演员）
 * @param blood    旧 blood（演员）
 * @param rid      旧 rid 的 intval（角色所属视频，非 0）
 * @param id       旧 id 的 intval（只有专题：Topic.php 的 paging 分支 {@code ids = intval(id)}，{@code ids} 设置时被覆盖）
 */
public record LibraryMatch(String keyword, String name, String tag, String classes, String ids, Long tid, Long level,
                           String letter, String sex, String area, String lang, String starSign, String blood,
                           Long rid, Long id) {

    public static final LibraryMatch NONE = new LibraryMatch(null, null, null, null, null, null);

    /** 逗号多值的项数上限（与视频的标签条件相同）。 */
    public static final int MAX_ITEMS = 100;

    private static final Set<String> ARTICLE = Set.of("wd", "name", "tag", "class", "ids", "tid", "level", "letter");
    private static final Set<String> TOPIC = Set.of("wd", "tag", "class", "ids", "id", "level", "letter");
    private static final Set<String> ACTOR = Set.of("wd", "ids", "level", "letter", "sex", "area", "starsign",
            "blood");
    private static final Set<String> ROLE = Set.of("wd", "ids", "level", "letter", "rid");
    private static final Set<String> WEBSITE = Set.of("wd", "name", "tag", "class", "ids", "level", "letter", "area",
            "lang");

    public LibraryMatch {
        // 与视频相同的文本规则（禁用字符、首尾空白、码点上限）；字段名按 content 的参数名报告
        new VideoFilter(keyword, area, lang, null, null, classes, letter, null, null, null, null, tag, name, ids);
        sex = VideoFilter.text("sex", sex, VideoFilter.MAX_LIST_LENGTH, true);
        starSign = VideoFilter.text("starSign", starSign, VideoFilter.MAX_LIST_LENGTH, true);
        blood = VideoFilter.text("blood", blood, VideoFilter.MAX_LIST_LENGTH, true);
        for (String[] v : new String[][] {{"letter", letter}, {"area", area}, {"lang", lang},
            {"starSign", starSign}, {"blood", blood}}) {
            if (v[1] != null && VideoFilter.items(v[1]).size() > MAX_ITEMS) {
                throw ApiException.invalidParameter(v[0], "最多 " + MAX_ITEMS + " 项");
            }
        }
        for (Long n : new Long[] {tid, level, rid, id}) {
            if (n != null && n == 0) {
                throw new IllegalArgumentException("intval 为 0 时应为未设置");
            }
        }
    }

    /** 只有包含匹配与 ID 条件（wd、name、tag、class、ids、tid）。 */
    public LibraryMatch(String keyword, String name, String tag, String classes, String ids, Long tid) {
        this(keyword, name, tag, classes, ids, tid, null, null, null, null, null, null, null, null, null);
    }

    /** 该类型接受的旧参数名（旧 listCacheData 的 paging=yes 分支：Art.php:164-227、Topic.php、Actor.php、Role.php、Website.php）。 */
    public static Set<String> supported(ContentKind kind) {
        return switch (kind) {
            case ARTICLE -> ARTICLE;
            case TOPIC -> TOPIC;
            case ACTOR -> ACTOR;
            case ROLE -> ROLE;
            case WEBSITE -> WEBSITE;
            case LINK -> Set.of();
        };
    }

    public boolean isEmpty() {
        return setParameters().isEmpty();
    }

    /** 是否含包含匹配条件（需要搜索开关与并发许可）。 */
    public boolean usesContainsMatch() {
        return keyword != null || name != null || tag != null || classes != null;
    }

    /** 第一个包含匹配条件的旧参数名（开关关闭时的 400 字段）。 */
    public String firstContainsMatchParameter() {
        return keyword != null ? "wd" : name != null ? "name" : tag != null ? "tag" : classes != null ? "class" : null;
    }

    /** 已设置的条件对应的旧参数名。 */
    List<String> setParameters() {
        List<String> out = new ArrayList<>();
        String[] names = {"wd", "name", "tag", "class", "ids", "tid", "level", "letter", "sex", "area", "lang",
            "starsign", "blood", "rid", "id"};
        Object[] values = {keyword, name, tag, classes, ids, tid, level, letter, sex, area, lang, starSign, blood, rid,
            id};
        for (int i = 0; i < names.length; i++) {
            if (values[i] != null) {
                out.add(names[i]);
            }
        }
        return out;
    }

    /** 检查该类型是否接受已设置的全部条件（不接受时是 site 的组装错误，不是用户输入错误）。 */
    void check(ContentKind kind) {
        for (String p : setParameters()) {
            if (!supported(kind).contains(p)) {
                throw new IllegalArgumentException(kind + " 不支持条件 " + p);
            }
        }
    }

    /**
     * Mapper 条件。{@code nameOrder} 为真时（网址 by=in 且有 name）另给出 FIND_IN_SET 的名单：旧版去掉引号与反斜线后拼入
     * orderRaw（Website.php:288-291），新版只去掉反斜线并经 {@code #{}} 绑定（旧版的值已经 htmlspecialchars，不含引号）。
     */
    ContentCriteria toCriteria(ContentKind kind, boolean nameOrder) {
        check(kind);
        VideoFilter f = new VideoFilter(keyword, area, lang, null, null, classes, letter, null, null, null, null, tag,
                name, ids);
        boolean website = kind == ContentKind.WEBSITE;
        List<Long> idList = f.idList();
        if (ids == null && id != null) {
            idList = List.of(id);
        }
        return new ContentCriteria(null, f.letters(), sex, null, null, null, null, rid, null, f.keywordPatterns(),
                website ? null : f.namePatterns(), website && name != null ? VideoFilter.items(name) : null,
                f.tagPatterns(), f.classesPatterns(), idList, tid, level, f.areas(), f.langs(),
                starSign == null ? null : VideoFilter.items(starSign), blood == null ? null : VideoFilter.items(blood),
                nameOrder ? name.replace("\\", "") : null);
    }
}

package com.maccms.backend.content.application;

import com.maccms.backend.content.infrastructure.ContentCriteria;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文章、专题、演员、角色、网址的 SSR 筛选/搜索页条件（B05，docs/architecture/15-b05-readonly-pages.md 第 7.2 节，队长裁定
 * D-B05-5）：旧 {@code listCacheData} 在 {@code paging=yes} 时从 URL 读取的 wd/name/tag/class/ids/tid。只由 site 构造，
 * 公开 API 不新增参数（不新增公开搜索 API）。
 *
 * <p>文本值沿用视频的同一套规则（{@link VideoFilter} 的紧凑构造器：禁用字符、首尾空白、长度上限、ids 的格式与项数），
 * LIKE 模式与视频相同（{@code ESCAPE '!'}，含 htmlspecialchars 变体）。各类型接受的条件见 {@link #supported(ContentKind)}；
 * 包含匹配条件（wd/name/tag/class，网址的 name 为精确 IN 但同样按搜索计）与视频列表共用开关、并发许可与执行时限
 * （{@link ContentReadService#searchEnabled()}），关闭时 400。
 *
 * @param keyword 旧 wd
 * @param name    旧 name（文章 LIKE，网址精确 IN）
 * @param tag     旧 tag
 * @param classes 旧 class
 * @param ids     旧 ids（{@code all} 为不筛选）
 * @param tid     旧 tid（只有文章；intval 后不为 0，负数照常筛选，与视频的 SSR tid 相同）
 */
public record LibraryMatch(String keyword, String name, String tag, String classes, String ids, Long tid) {

    public static final LibraryMatch NONE = new LibraryMatch(null, null, null, null, null, null);

    private static final Set<String> ARTICLE = Set.of("wd", "name", "tag", "class", "ids", "tid");
    private static final Set<String> TOPIC = Set.of("wd", "tag", "class", "ids");
    private static final Set<String> ACTOR_ROLE = Set.of("wd", "ids");
    private static final Set<String> WEBSITE = Set.of("wd", "name", "tag", "class", "ids");

    public LibraryMatch {
        // 与视频相同的文本规则（字段名按旧参数报告）
        new VideoFilter(keyword, null, null, null, null, classes, null, null, null, null, null, tag, name, ids);
        if (tid != null && tid == 0) {
            throw new IllegalArgumentException("tid 为 0 时应为未设置");
        }
    }

    /** 该类型接受的旧参数名（旧 listCacheData 的 paging=yes 分支：Art.php:166-196、Topic.php:98-128 等）。 */
    public static Set<String> supported(ContentKind kind) {
        return switch (kind) {
            case ARTICLE -> ARTICLE;
            case TOPIC -> TOPIC;
            case ACTOR, ROLE -> ACTOR_ROLE;
            case WEBSITE -> WEBSITE;
            case LINK -> Set.of();
        };
    }

    public boolean isEmpty() {
        return keyword == null && name == null && tag == null && classes == null && ids == null && tid == null;
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
        if (keyword != null) {
            out.add("wd");
        }
        if (name != null) {
            out.add("name");
        }
        if (tag != null) {
            out.add("tag");
        }
        if (classes != null) {
            out.add("class");
        }
        if (ids != null) {
            out.add("ids");
        }
        if (tid != null) {
            out.add("tid");
        }
        return out;
    }

    /** 并入精确筛选条件；{@code websiteNames} 为真时 name 是精确 IN（网址），否则是 LIKE（文章）。 */
    ContentCriteria apply(ContentCriteria base, ContentKind kind) {
        for (String p : setParameters()) {
            if (!supported(kind).contains(p)) {
                throw new IllegalArgumentException(kind + " 不支持条件 " + p);
            }
        }
        if (isEmpty()) {
            return base;
        }
        VideoFilter f = new VideoFilter(keyword, null, null, null, null, classes, null, null, null, null, null, tag,
                name, ids);
        boolean website = kind == ContentKind.WEBSITE;
        return new ContentCriteria(base.level(), base.letters(), base.sex(), base.area(), base.lang(), base.starSign(),
                base.blood(), base.videoId(), base.linkType(), f.keywordPatterns(), website ? null : f.namePatterns(),
                website && name != null ? VideoFilter.items(name) : null, f.tagPatterns(), f.classesPatterns(),
                f.idList(), tid);
    }
}

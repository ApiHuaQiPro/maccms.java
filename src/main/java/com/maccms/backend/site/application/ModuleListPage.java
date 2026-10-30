package com.maccms.backend.site.application;

import com.maccms.backend.content.application.ContentKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文章、专题、演员、角色、网址、剧情的筛选页（旧 {@code show}）、搜索页（旧 {@code search}）与它们的 ajax 片段（B05，
 * docs/architecture/15-b05-readonly-pages.md 第 7.2 节，队长裁定 D-B05-5 与独立验收返工第 1、2 项）。每一项固定旧"控制器/操作"、
 * 内容类型与接受的旧参数名（旧 {@code listCacheData} 在 {@code paging=yes} 时从 URL 读取的键，application/common/model/*.php；
 * {@code ajax} 按 D-B05-6 接受并忽略，{@code page} 为页码）。
 *
 * <p>{@code role/show}、{@code role/ajax_show}（Role.php:18-31）调用 {@code label_role()}，与搜索页读同一组 URL 参数，没有分类
 * （{@link #categorized()} 为假）。旧控制器没有 {@code topic/show}、{@code plot/show}，不提供。
 */
public enum ModuleListPage {
    ART_SEARCH("art", "search", ContentKind.ARTICLE),
    ART_AJAX_SEARCH("art", "ajax_search", ContentKind.ARTICLE),
    ART_SHOW("art", "show", ContentKind.ARTICLE),
    ART_AJAX_SHOW("art", "ajax_show", ContentKind.ARTICLE),
    TOPIC_SEARCH("topic", "search", ContentKind.TOPIC),
    TOPIC_AJAX_SEARCH("topic", "ajax_search", ContentKind.TOPIC),
    ACTOR_SEARCH("actor", "search", ContentKind.ACTOR),
    ACTOR_AJAX_SEARCH("actor", "ajax_search", ContentKind.ACTOR),
    ACTOR_SHOW("actor", "show", ContentKind.ACTOR),
    ACTOR_AJAX_SHOW("actor", "ajax_show", ContentKind.ACTOR),
    ROLE_SEARCH("role", "search", ContentKind.ROLE),
    ROLE_AJAX_SEARCH("role", "ajax_search", ContentKind.ROLE),
    ROLE_SHOW("role", "show", ContentKind.ROLE),
    ROLE_AJAX_SHOW("role", "ajax_show", ContentKind.ROLE),
    WEBSITE_SEARCH("website", "search", ContentKind.WEBSITE),
    WEBSITE_AJAX_SEARCH("website", "ajax_search", ContentKind.WEBSITE),
    WEBSITE_SHOW("website", "show", ContentKind.WEBSITE),
    WEBSITE_AJAX_SHOW("website", "ajax_show", ContentKind.WEBSITE),
    /** 旧 plot/search：有分集剧情的视频（模板标签 plot=1），条件经旧 Vod::listCacheData 读取，与 vod/search 同一组键。 */
    PLOT_SEARCH("plot", "search", null),
    PLOT_AJAX_SEARCH("plot", "ajax_search", null);

    /** 各模块接受的旧参数名（不含 page、ajax；带分类的筛选页另有 id）。 */
    private static final Map<String, Set<String>> KEYS = Map.of(
            "art", Set.of("ids", "tid", "level", "letter", "wd", "name", "tag", "class", "by", "order"),
            "topic", Set.of("id", "ids", "level", "letter", "wd", "tag", "class", "by", "order"),
            "actor", Set.of("ids", "level", "letter", "sex", "area", "starsign", "blood", "wd", "by", "order"),
            "role", Set.of("rid", "ids", "level", "letter", "wd", "by", "order"),
            "website", Set.of("ids", "level", "letter", "area", "lang", "wd", "name", "tag", "class", "by", "order"),
            // Vod.php:167-247 的 paging 分支（isend 经 mac_param_url 读不到，15 文档第 3.1 节）
            "plot", Set.of("wd", "area", "lang", "year", "class", "level", "by", "order", "actor", "director", "tag",
                    "letter", "state", "version", "name", "ids", "tid"));

    private static final Map<String, ModuleListPage> BY_TARGET = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(ModuleListPage::target, Function.identity()));

    private final String module;
    private final String action;
    private final ContentKind kind;

    ModuleListPage(String module, String action, ContentKind kind) {
        this.module = module;
        this.action = action;
        this.kind = kind;
    }

    public String module() {
        return module;
    }

    /** 旧"控制器/操作"。 */
    public String target() {
        return module + "/" + action;
    }

    /** 内容类型；剧情（视频）为 null。 */
    public ContentKind kind() {
        return kind;
    }

    /** 筛选页（旧 check_show：受 app.show 开关）。 */
    public boolean show() {
        return action.endsWith("show");
    }

    /** 带分类的筛选页（文章、演员、网址的 show；角色的 show 没有分类）。 */
    public boolean categorized() {
        return show() && kind != null && kind.hasCategory();
    }

    public boolean ajax() {
        return action.startsWith("ajax_");
    }

    /** 对应的完整页面（ajax 片段中的分页链接指向它）。 */
    public ModuleListPage page() {
        return ajax() ? byTarget(module + "/" + action.substring("ajax_".length())) : this;
    }

    /** 接受的旧条件键（不含 page、ajax 与筛选页的分类 id）。 */
    public Set<String> keys() {
        return KEYS.get(module);
    }

    /** 链接与隐藏字段中条件键的输出顺序（按键排序，与 mac_url 一致）。 */
    public List<String> sortedKeys() {
        return keys().stream().sorted().toList();
    }

    /** 按旧"控制器/操作"查找；不是本批承接的页面时为 null。 */
    public static ModuleListPage byTarget(String target) {
        return BY_TARGET.get(target);
    }
}

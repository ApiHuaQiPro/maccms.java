package com.maccms.backend.site.application;

import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.VideoListQuery;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * B05 承接的旧"模块/操作 + 数字参数"页面（docs/architecture/15-b05-readonly-pages.md 第 5、6、7 节）。每一项固定：旧控制器/操作、
 * 可用的路径参数与旧 {@code mac_url} 生成时的参数顺序（PHP 黄金向量 {@code site/url-golden/b05-vectors.json}，
 * 由 {@code src/test/php/b05_url_golden.php} 生成）。
 *
 * <p>参数顺序：{@code vod/play}、{@code vod/down} 由 mac_url 的专门分支按 id、sid、nid 重建（application/common.php:1883-1965）；
 * {@code vod/player}、{@code vod/downer} 走默认分支，参数经 {@code ksort} 为 id、nid、sid（:1734-1746、All.php:554）；其余为 id、page。
 * 页码 1 不输出（mac_url 把 page=1 置空）。
 */
public enum LegacyPage {
    VOD_PLAY("vod/play", Id.VIDEO, List.of("id", "sid", "nid"), false),
    VOD_PLAYER("vod/player", Id.VIDEO, List.of("id", "nid", "sid"), false),
    VOD_DOWN("vod/down", Id.VIDEO, List.of("id", "sid", "nid"), false),
    VOD_DOWNER("vod/downer", Id.VIDEO, List.of("id", "nid", "sid"), false),
    VOD_PLOT("vod/plot", Id.VIDEO, List.of("id", "page"), false),
    VOD_ROLE("vod/role", Id.VIDEO, List.of("id"), false),
    VOD_RSS("vod/rss", Id.VIDEO, List.of("id"), true),
    VOD_AJAX_DETAIL("vod/ajax_detail", Id.VIDEO, List.of("id"), false),

    ART_INDEX("art/index", Id.NONE, List.of("page"), false),
    ART_TYPE("art/type", Id.CATEGORY, List.of("id", "page"), false),
    ART_DETAIL("art/detail", Id.CONTENT, List.of("id", "page"), false),
    ART_RSS("art/rss", Id.CONTENT, List.of("id"), true),
    ART_AJAX_DETAIL("art/ajax_detail", Id.CONTENT, List.of("id"), false),

    TOPIC_INDEX("topic/index", Id.NONE, List.of("page"), false),
    TOPIC_DETAIL("topic/detail", Id.CONTENT, List.of("id"), false),
    TOPIC_RSS("topic/rss", Id.CONTENT, List.of("id"), true),
    TOPIC_AJAX_DETAIL("topic/ajax_detail", Id.CONTENT, List.of("id"), false),

    ACTOR_INDEX("actor/index", Id.NONE, List.of("page"), false),
    ACTOR_TYPE("actor/type", Id.CATEGORY, List.of("id", "page"), false),
    ACTOR_DETAIL("actor/detail", Id.CONTENT, List.of("id"), false),
    ACTOR_RSS("actor/rss", Id.CONTENT, List.of("id"), true),
    ACTOR_AJAX_DETAIL("actor/ajax_detail", Id.CONTENT, List.of("id"), false),

    ROLE_INDEX("role/index", Id.NONE, List.of("page"), false),
    ROLE_DETAIL("role/detail", Id.CONTENT, List.of("id"), false),
    ROLE_RSS("role/rss", Id.CONTENT, List.of("id"), true),
    ROLE_AJAX_DETAIL("role/ajax_detail", Id.CONTENT, List.of("id"), false),

    WEBSITE_INDEX("website/index", Id.NONE, List.of("page"), false),
    WEBSITE_TYPE("website/type", Id.CATEGORY, List.of("id", "page"), false),
    WEBSITE_DETAIL("website/detail", Id.CONTENT, List.of("id"), false),
    WEBSITE_RSS("website/rss", Id.CONTENT, List.of("id"), true),
    WEBSITE_AJAX_DETAIL("website/ajax_detail", Id.CONTENT, List.of("id"), false),

    PLOT_INDEX("plot/index", Id.NONE, List.of("page"), false),
    PLOT_DETAIL("plot/detail", Id.VIDEO, List.of("id", "page"), false),
    PLOT_RSS("plot/rss", Id.VIDEO, List.of("id"), true),
    PLOT_AJAX_DETAIL("plot/ajax_detail", Id.VIDEO, List.of("id"), false);

    /** id 参数的取值范围。 */
    public enum Id {
        /** 没有 id。 */
        NONE(0),
        /** 视频 vod_id int unsigned。 */
        VIDEO(VideoId.MAX),
        /** 分类 type_id smallint unsigned（与 vod/type 相同）。 */
        CATEGORY(VideoListQuery.MAX_CATEGORY_ID),
        /** 文章、专题、演员、角色、网址的 id（int unsigned，与视频相同上限）。 */
        CONTENT(VideoId.MAX);

        private final long max;

        Id(long max) {
            this.max = max;
        }

        public long max() {
            return max;
        }
    }

    /** 播放源序号 sid 的上限（旧 vod_play_from 为 varchar(255)，以 "$$$" 分隔的组不会超过它）。 */
    public static final int MAX_SID = 255;
    /** 集序号 nid 的上限（旧集数没有上限；vod_play_url 为 mediumtext，取一个宽松的上限）。 */
    public static final int MAX_NID = 99999;

    private static final Map<String, LegacyPage> BY_TARGET = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(LegacyPage::target, Function.identity()));

    private final String target;
    private final Id id;
    private final List<String> keys;
    private final boolean rss;

    LegacyPage(String target, Id id, List<String> keys, boolean rss) {
        this.target = target;
        this.id = id;
        this.keys = keys;
        this.rss = rss;
    }

    /** 旧"控制器/操作"（小写）。 */
    public String target() {
        return target;
    }

    public Id id() {
        return id;
    }

    /** 可用的路径参数，也是生成地址时的输出顺序。 */
    public List<String> keys() {
        return keys;
    }

    /** 是否为单条 RSS（application/rss+xml 响应）。 */
    public boolean rss() {
        return rss;
    }

    /** 是否需要 sid、nid（播放/下载四个入口）。 */
    public boolean episode() {
        return keys.contains("sid");
    }

    /** 是否接受页码。 */
    public boolean paged() {
        return keys.contains("page");
    }

    /** 按旧"控制器/操作"查找；不是本批承接的页面时为 null。 */
    public static LegacyPage byTarget(String target) {
        return BY_TARGET.get(target);
    }
}

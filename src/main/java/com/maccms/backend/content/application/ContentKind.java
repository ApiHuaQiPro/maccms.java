package com.maccms.backend.content.application;

import com.maccms.backend.content.infrastructure.SortColumn;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约 1.2.0 的六类列表（文章、专题、演员、角色、网址、友链）各自允许的参数：排序白名单（对外取值 → 列）、默认排序与方向、
 * 是否按分类筛选、可用的精确筛选参数。排序白名单取自各模型 listCacheData 的 by（行号见 docs/architecture/11-content-read-completion.md
 * 第 4.2 节），不含 rnd（随机页）与 in（按模板给定名单排序，请求参数无法到达）。
 */
public enum ContentKind {
    ARTICLE(2, Sorts.COMMON, SortColumn.TIME, true, List.of("level", "letter")),
    TOPIC(0, Sorts.COMMON, SortColumn.TIME, true, List.of("level", "letter")),
    ACTOR(8, Sorts.COMMON, SortColumn.TIME, true, List.of("level", "letter", "sex", "area", "starSign", "blood")),
    ROLE(0, Sorts.COMMON, SortColumn.TIME, true, List.of("level", "letter", "videoId")),
    WEBSITE(11, Sorts.WEBSITE, SortColumn.TIME, true, List.of("level", "letter", "area", "lang")),
    /** 旧 Link::listCacheData：by 只有 id/sort，默认 id、方向默认 asc（application/common/model/Link.php:53-58）。 */
    LINK(0, Sorts.LINK, SortColumn.ID, false, List.of("type"));

    private final int typeMid;
    private final Map<String, SortColumn> sorts;
    private final SortColumn defaultSort;
    private final boolean defaultDescending;
    private final List<String> filters;

    ContentKind(int typeMid, Map<String, SortColumn> sorts, SortColumn defaultSort, boolean defaultDescending,
                List<String> filters) {
        this.typeMid = typeMid;
        this.sorts = sorts;
        this.defaultSort = defaultSort;
        this.defaultDescending = defaultDescending;
        this.filters = filters;
    }

    /** 分类所属模型 type_mid（2 文章、8 演员、11 网址）；0 表示该类型没有分类，不接受 categoryId。 */
    public int typeMid() {
        return typeMid;
    }

    public boolean hasCategory() {
        return typeMid != 0;
    }

    public Map<String, SortColumn> sorts() {
        return sorts;
    }

    public SortColumn defaultSort() {
        return defaultSort;
    }

    public boolean defaultDescending() {
        return defaultDescending;
    }

    /** 可用的精确筛选参数名（按校验顺序）。 */
    public List<String> filters() {
        return filters;
    }

    private static final class Sorts {
        /** Art/Topic/Actor/Role 的 by：Art.php:370、Topic.php:235、Actor.php:304、Role.php:247。 */
        static final Map<String, SortColumn> COMMON = of("time", SortColumn.TIME, "time_add", SortColumn.TIME_ADD,
                "id", SortColumn.ID, "score", SortColumn.SCORE, "hits", SortColumn.HITS, "hits_day", SortColumn.HITS_DAY,
                "hits_week", SortColumn.HITS_WEEK, "hits_month", SortColumn.HITS_MONTH, "up", SortColumn.UP,
                "down", SortColumn.DOWN, "level", SortColumn.LEVEL);
        /** Website.php:393 另有 referer、referer_day、referer_week、referer_month。 */
        static final Map<String, SortColumn> WEBSITE;
        static final Map<String, SortColumn> LINK = of("id", SortColumn.ID, "sort", SortColumn.SORT);

        static {
            Map<String, SortColumn> m = new LinkedHashMap<>(COMMON);
            m.put("referer", SortColumn.REFERER);
            m.put("referer_day", SortColumn.REFERER_DAY);
            m.put("referer_week", SortColumn.REFERER_WEEK);
            m.put("referer_month", SortColumn.REFERER_MONTH);
            WEBSITE = Collections.unmodifiableMap(m);
        }

        private static Map<String, SortColumn> of(Object... kv) {
            Map<String, SortColumn> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], (SortColumn) kv[i + 1]);
            }
            return Collections.unmodifiableMap(m);
        }
    }
}

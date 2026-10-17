package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.content.application.view.LegacyTypeDto;
import com.maccms.backend.content.infrastructure.LegacyRowCriteria;
import com.maccms.backend.content.infrastructure.LegacyRowMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 旧协议的整行读取（B06，docs/architecture/16-b06-misc-pages-provide.md 第 3.5、3.6、4.9、4.10 节）：Provide 的
 * {@code ac=list/videolist/detail}、ajax/data、ajax/suggest 需要旧版 {@code SELECT *} 或固定字段的行。
 *
 * <ul>
 * <li>行范围始终是"已审核（{@code <前缀>_status = 1}）+ 可见分类"（视频/文章/演员/网址按 {@link CategoryCatalog}，与 content API 相同；
 * 专题、角色没有分类，只看状态）。调用方的条件只能再收窄。可见分类为空时直接返回空页，不查库。</li>
 * <li>访问密码列从不读出；{@code vod_pwd} 非空时只在详情页出现的内容 {@code vod_content/vod_plot_name/vod_plot_detail} 与
 * {@code vod_play_url/vod_down_url} 置空串，{@code vod_pwd_play} 非空时 {@code vod_play_url} 置空串，{@code vod_pwd_down} 非空时
 * {@code vod_down_url} 置空串，{@code art_pwd} 非空时 {@code art_content/art_title/art_note}（分页正文、页标题、页备注）置空串
 * （16 文档第 3.6 节）。</li>
 * <li>包含匹配（名称、英文名、播放组）按字面值匹配（{@code ESCAPE '!'}），与搜索/筛选共用进程内并发许可（用尽 → 503）与 Mapper 的
 * 执行时限（超时 → 503）；搜索开关由调用方决定（ajax/suggest 受它约束，Provide 不受，16 文档 D-B06-6）。</li>
 * </ul>
 */
@Service
public class LegacyRowReadService {

    private static final Logger log = LoggerFactory.getLogger(LegacyRowReadService.class);
    /** mac_type 的列顺序（install.sql:574-594）。 */
    static final List<String> TYPE_COLUMNS = List.of("type_id", "type_name", "type_en", "type_sort", "type_mid", "type_pid",
            "type_status", "type_tpl", "type_tpl_list", "type_tpl_detail", "type_tpl_play", "type_tpl_down", "type_key",
            "type_des", "type_title", "type_union", "type_extend", "type_logo", "type_pic", "type_jumpurl");
    private static final List<String> SUGGEST_COLUMNS = List.of("id", "name", "en", "pic");
    private static final List<String> BRIEF_COLUMNS = List.of("vod_id", "vod_douban_id", "vod_name", "vod_director");
    private static final List<Integer> TYPED_MIDS = List.of(1, 2, 8, 11);

    private final LegacyRowMapper mapper;
    private final ContentCatalogs catalogs;
    private final ContentReadService content;

    public LegacyRowReadService(LegacyRowMapper mapper, ContentCatalogs catalogs, ContentReadService content) {
        this.mapper = mapper;
        this.catalogs = catalogs;
        this.content = content;
    }

    /** 一页行与总数。 */
    public LegacyRowPageDto read(LegacyRowQuery query) {
        return guarded(query.usesContainsMatch(), () -> doRead(query));
    }

    /**
     * 全部分类（不论状态），按 type_id 升序，附可见标记。
     */
    public List<LegacyTypeDto> types() {
        return ContentReadService.read(() -> {
            Set<Integer> visible = new HashSet<>();
            GuestPermission guest = catalogs.guest();
            for (int mid : TYPED_MIDS) {
                visible.addAll(visibleTypeIds(mid, guest));
            }
            List<LegacyTypeDto> out = new ArrayList<>();
            for (Map<String, Object> raw : mapper.selectTypes()) {
                LegacyRowDto row = ordered(raw, TYPE_COLUMNS);
                int id = (int) row.number("type_id");
                out.add(new LegacyTypeDto(row, id, (int) row.number("type_pid"), (int) row.number("type_mid"),
                        visible.contains(id)));
            }
            return out;
        });
    }

    /**
     * 已审核且分类可见的视频（角色的关联视频，16 文档第 3.6 节），按 vod_id 取。不在范围内的 id 不出现在结果中。
     *
     * @param ids     vod_id（≤ {@value LegacyRowQuery#MAX_IDS} 个，空集合直接返回空结果）
     * @param columns {@link LegacyColumnSet#BRIEF} 或 {@link LegacyColumnSet#FULL}
     */
    public Map<Long, LegacyRowDto> visibleVideos(Collection<Long> ids, LegacyColumnSet columns) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> keys = ids.stream().distinct().map(String::valueOf).toList();
        LegacyRowQuery q = new LegacyRowQuery(LegacyTable.VOD, columns, LegacyRowOrder.ID_DESC, keys, null, null, null,
                null, null, null, null, null, null, null, null, 0, keys.size());
        Map<Long, LegacyRowDto> out = new LinkedHashMap<>();
        for (LegacyRowDto row : read(q).rows()) {
            out.put(row.number("vod_id"), row);
        }
        return out;
    }

    private LegacyRowPageDto doRead(LegacyRowQuery q) {
        List<Integer> typeIds = null;
        if (q.table().typed()) {
            typeIds = List.copyOf(visibleTypeIds(q.table().mid(), catalogs.guest()));
            if (typeIds.isEmpty()) {
                return new LegacyRowPageDto(0, List.of());
            }
        }
        LegacyRowCriteria c = new LegacyRowCriteria(
                LegacyRowCriteria.Table.valueOf(q.table().name()), LegacyRowCriteria.Columns.valueOf(q.columns().name()),
                LegacyRowCriteria.Order.valueOf(q.order().name()), typeIds, q.typeIdEquals(), q.typeIdIn(),
                q.typeOrParentIn(), q.ids(), q.timeAfter(), q.timeBefore(),
                q.nameContains() == null ? null : VideoFilter.contains(q.nameContains()),
                q.nameOrEnContains() == null ? null : VideoFilter.contains(q.nameOrEnContains()), q.isEnd(), q.years(),
                q.playFromContains() == null ? null : q.playFromContains().stream().map(VideoFilter::contains).toList(),
                q.conditions() == null ? null : q.conditions().stream().map(x -> new LegacyRowCriteria.Condition(
                        LegacyRowCriteria.Column.valueOf(x.column().name()), LegacyRowCriteria.Op.valueOf(x.op().name()),
                        x.value())).toList(),
                q.offset(), q.limit());
        long total = mapper.count(c);
        if (total == 0 || q.offset() >= total) {
            return new LegacyRowPageDto(total, List.of());
        }
        List<String> order = switch (q.columns()) {
            case FULL -> q.table().fullColumns();
            case LIST -> q.table().listColumns();
            case SUGGEST -> SUGGEST_COLUMNS;
            case BRIEF -> BRIEF_COLUMNS;
        };
        List<LegacyRowDto> rows = new ArrayList<>();
        for (Map<String, Object> raw : mapper.select(c)) {
            rows.add(ordered(protect(q.table(), raw), order));
        }
        return new LegacyRowPageDto(total, rows);
    }

    /** 访问密码保护（16 文档第 3.6 节）：按 SQL 给出的布尔标记置空受保护的正文与地址，并去掉标记。 */
    static Map<String, Object> protect(LegacyTable table, Map<String, Object> raw) {
        Map<String, Object> m = new HashMap<>(raw);
        boolean detail = Boolean.TRUE.equals(m.remove("_pwd"));
        boolean play = Boolean.TRUE.equals(m.remove("_pwd_play"));
        boolean down = Boolean.TRUE.equals(m.remove("_pwd_down"));
        if (table == LegacyTable.VOD) {
            if (detail) {
                blank(m, "vod_content");
                blank(m, "vod_plot_name");
                blank(m, "vod_plot_detail");
            }
            if (detail || play) {
                blank(m, "vod_play_url");
            }
            if (detail || down) {
                blank(m, "vod_down_url");
            }
        } else if (table == LegacyTable.ART && detail) {
            blank(m, "art_content");
            blank(m, "art_title");
            blank(m, "art_note");
        }
        return m;
    }

    private static void blank(Map<String, Object> m, String column) {
        if (m.containsKey(column)) {
            m.put(column, "");
        }
    }

    /** 按给定列顺序取值；结果集中没有的列为 null（只有旧版补出的 type_name）。 */
    private static LegacyRowDto ordered(Map<String, Object> raw, List<String> columns) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String col : columns) {
            m.put(col, raw.get(col));
        }
        return new LegacyRowDto(m);
    }

    private Set<Integer> visibleTypeIds(int mid, GuestPermission guest) {
        CategoryCatalog catalog = mid == 1 ? catalogs.vodCatalog(guest) : catalogs.catalog(mid, guest);
        return catalog.allIds();
    }

    private <T> T guarded(boolean containsMatch, Supplier<T> query) {
        if (!containsMatch) {
            return ContentReadService.read(query);
        }
        if (!content.tryAcquireSearchPermit()) {
            log.warn("legacy row read rejected: concurrency limit reached");
            throw ContentReadService.unavailable();
        }
        try {
            return ContentReadService.read(query);
        } finally {
            content.releaseSearchPermit();
        }
    }
}

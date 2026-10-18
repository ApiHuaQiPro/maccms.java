package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.LegacyColumnSet;
import com.maccms.backend.content.application.LegacyRowOrder;
import com.maccms.backend.content.application.LegacyRowQuery;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.content.application.view.LegacyTypeDto;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyParams;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.ModuleFilters;
import com.maccms.backend.site.application.ModuleListPage;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteLink;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 旧 {@code ajax/data} 与 {@code ajax/suggest} 的 JSON（index/controller/Ajax.php:21-121，16 文档第 4.9、4.10 节）。结构与旧版相同：
 * 外层 {@code code,msg,page,pagecount,limit,total,list}（联想词另有 {@code url}）；列表行是旧表整行（{@code SELECT *}，去掉访问密码列），
 * 旧 {@code listData} 附加的 {@code type}/{@code type_1}（分类整行，{@code type_extend} 解码，顶级分类带 {@code childids}，子分类带上级的
 * {@code type_1}）与角色的 {@code data}（关联视频）照旧输出；专题的列表以 topic_id 为键（旧 {@code Topic::listData}）。
 *
 * <p>与旧版的差异（第 7 节）：只含已审核 + 可见分类；会员组 {@code group} 不输出；角色的关联视频只在已审核且分类可见时给出，且与视频一样
 * 不含播放/下载地址；图片按 {@link ImageUrlNormalizer}（空值为空串，W2 关闭时外部图片为空串）；联想词的 {@code url} 指向 Java 承接的
 * 查询串形式；联想词按字面值匹配、受搜索开关与并发许可约束、条数上限 50。
 */
public class AjaxPages {

    static final Set<Long> MIDS = Set.of(1L, 2L, 3L, 8L, 9L, 11L);
    static final int SUGGEST_MAX = 50;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> MEDIA = List.of("vod_play_from", "vod_play_server", "vod_play_note", "vod_play_url",
            "vod_down_from", "vod_down_server", "vod_down_note", "vod_down_url");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LegacyRowReadService rows;
    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final ImageUrlNormalizer images;

    public AjaxPages(LegacyRowReadService rows, SiteSettings site, LegacyUrlBuilder urls, ImageUrlNormalizer images) {
        this.rows = rows;
        this.site = site;
        this.urls = urls;
        this.images = images;
    }

    static Map<String, Object> code(long code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("msg", msg);
        return m;
    }

    /** ajax/data（Ajax.php:21-86）。 */
    public Map<String, Object> data(LegacyParams p) {
        long mid = p.intval("mid");
        long limit = p.intval("limit");
        long page = p.intval("page") < 1 ? 1 : p.intval("page");
        long tid = p.intval("tid");
        if (!MIDS.contains(mid)) {
            return code(1001, "参数错误");
        }
        if (limit != 10 && limit != 20 && limit != 30) {
            limit = 10;
        }
        if (page < 1 || page > 20) {
            page = 1;
        }
        LegacyTable table = LegacyTable.byMid((int) mid).orElseThrow();
        List<LegacyTypeDto> types = table.typed() ? rows.types() : List.of();
        TypeObjects typeObjects = new TypeObjects(types);
        List<Integer> typeOrParent = null;
        if ((mid == 1 || mid == 2) && tid != 0 && typeObjects.exists(tid)) {
            typeOrParent = typeObjects.scope((int) tid);
        }
        LegacyRowQuery q = new LegacyRowQuery(table, LegacyColumnSet.FULL, LegacyRowOrder.TIME_DESC, null, null, null,
                typeOrParent, null, null, null, null, null, null, null, null, (page - 1) * limit, (int) limit);
        LegacyRowPageDto result = rows.read(q);
        Map<Long, LegacyRowDto> related = table == LegacyTable.ROLE ? relatedVideos(result) : Map.of();
        TypeObjects vodTypes = table == LegacyTable.ROLE ? new TypeObjects(rows.types()) : typeObjects;
        String pre = table.prefix();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LegacyRowDto row : result.rows()) {
            Map<String, Object> v = new LinkedHashMap<>(row.fields());
            addTypes(v, table, typeObjects);
            if (table == LegacyTable.ROLE) {
                LegacyRowDto vod = related.get(row.number("role_rid"));
                v.put("data", vod == null ? null : relatedVideo(vod, vodTypes));
            }
            v.remove(pre + "_time_hits");
            v.remove(pre + "_time_make");
            v.put(pre + "_time", datetime(row.number(pre + "_time")));
            v.put(pre + "_time_add", datetime(row.number(pre + "_time_add")));
            if (table == LegacyTable.VOD) {
                MEDIA.forEach(v::remove);
            }
            v.put("detail_link", detailLink(table, row.number(pre + "_id")));
            for (String suffix : List.of("_pic", "_pic_thumb", "_pic_slide")) {
                v.put(pre + suffix, image(v.get(pre + suffix)));
            }
            list.add(v);
        }
        return page(result, page, limit, keyed(table, list));
    }

    /** ajax/suggest（Ajax.php:88-121）；开关由调用方判断。 */
    public Map<String, Object> suggest(LegacyParams p) {
        long mid = p.intval("mid");
        Optional<String> wd = p.text("wd");
        long limit = p.intval("limit");
        if (wd.isEmpty() || wd.get().isEmpty() || !MIDS.contains(mid)) {
            return code(1001, "参数错误");
        }
        if (limit < 1) {
            limit = 20;
        }
        limit = Math.min(limit, SUGGEST_MAX);
        LegacyTable table = LegacyTable.byMid((int) mid).orElseThrow();
        // 旧 Topic::listData 不用 field 参数（Topic.php:42），返回整行并以 topic_id 为键
        LegacyColumnSet columns = table == LegacyTable.TOPIC ? LegacyColumnSet.FULL : LegacyColumnSet.SUGGEST;
        LegacyRowQuery q;
        try {
            q = new LegacyRowQuery(table, columns, LegacyRowOrder.ID_DESC, null, null, null, null, null, null, null,
                    wd.get(), null, null, null, null, 0, (int) limit);
        } catch (IllegalArgumentException e) {
            return code(1001, "参数错误");
        }
        LegacyRowPageDto result = rows.read(q);
        List<Map<String, Object>> list = new ArrayList<>();
        for (LegacyRowDto row : result.rows()) {
            Map<String, Object> v = new LinkedHashMap<>(row.fields());
            if (table == LegacyTable.ROLE) {
                // 旧 Role::listData 附加关联视频（Role.php:49-66），联想词的字段里没有 role_rid，结果总是 null
                v.put("data", null);
            }
            v.put("pic", image(v.get("pic")));
            list.add(v);
        }
        Map<String, Object> out = page(result, 1, limit, keyed(table, list));
        out.put("url", suggestUrl(table));
        return out;
    }

    private static Map<String, Object> page(LegacyRowPageDto result, long page, long limit, Object list) {
        Map<String, Object> m = code(1, "数据列表");
        m.put("page", page);
        m.put("pagecount", (result.total() + limit - 1) / limit);
        m.put("limit", limit);
        m.put("total", result.total());
        m.put("list", list);
        return m;
    }

    /** 专题以 topic_id 为键（对象）；空列表与其他模块为数组。 */
    private static Object keyed(LegacyTable table, List<Map<String, Object>> list) {
        if (table != LegacyTable.TOPIC || list.isEmpty()) {
            return list;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map<String, Object> v : list) {
            m.put(String.valueOf(v.get("topic_id")), v);
        }
        return m;
    }

    private Map<Long, LegacyRowDto> relatedVideos(LegacyRowPageDto result) {
        List<Long> ids = new ArrayList<>();
        for (LegacyRowDto r : result.rows()) {
            ids.add(r.number("role_rid"));
        }
        return rows.visibleVideos(ids, LegacyColumnSet.FULL);
    }

    private Map<String, Object> relatedVideo(LegacyRowDto vod, TypeObjects types) {
        Map<String, Object> v = new LinkedHashMap<>(vod.fields());
        MEDIA.forEach(v::remove);
        addTypes(v, LegacyTable.VOD, types);
        return v;
    }

    /** 旧 listData 的 addition：{@code type}、{@code type_1}（type_id 非空时，Vod.php:55-60 等）。 */
    private static void addTypes(Map<String, Object> v, LegacyTable table, TypeObjects types) {
        if (!table.typed()) {
            return;
        }
        Object typeId = v.get("type_id");
        if (!(typeId instanceof Long id) || id == 0) {
            return;
        }
        Map<String, Object> type = types.object(id.intValue());
        v.put("type", type);
        v.put("type_1", type == null ? null : types.object(((Long) type.get("type_pid")).intValue()));
    }

    private String image(Object raw) {
        return images.normalize(raw == null ? null : raw.toString()).map(SafeUrl::href).orElse("");
    }

    private String detailLink(LegacyTable table, long id) {
        String path = switch (table) {
            case VOD -> urls.vodDetail(id);
            case ART -> urls.legacy(SiteRoute.Legacy.paged(LegacyPage.ART_DETAIL, id, 1));
            case TOPIC -> urls.legacy(SiteRoute.Legacy.of(LegacyPage.TOPIC_DETAIL, id));
            case ACTOR -> urls.legacy(SiteRoute.Legacy.of(LegacyPage.ACTOR_DETAIL, id));
            case ROLE -> urls.legacy(SiteRoute.Legacy.of(LegacyPage.ROLE_DETAIL, id));
            case WEBSITE -> urls.legacy(SiteRoute.Legacy.of(LegacyPage.WEBSITE_DETAIL, id));
        };
        return site.publicPath(path).href();
    }

    /** {@code mac_url_search(['wd'=>'mac_wd'], <模块>)}：新版指向 Java 承接的查询串形式（{@code <模块>/search.html?wd=mac_wd}）。 */
    private String suggestUrl(LegacyTable table) {
        String base = switch (table) {
            case VOD -> urls.vodSearch(Map.of(), 1);
            case ART -> moduleSearch(ModuleListPage.ART_SEARCH);
            case TOPIC -> moduleSearch(ModuleListPage.TOPIC_SEARCH);
            case ACTOR -> moduleSearch(ModuleListPage.ACTOR_SEARCH);
            case ROLE -> moduleSearch(ModuleListPage.ROLE_SEARCH);
            case WEBSITE -> moduleSearch(ModuleListPage.WEBSITE_SEARCH);
        };
        return site.publicPath(new SiteLink(base, "wd=mac_wd")).href();
    }

    private String moduleSearch(ModuleListPage page) {
        return urls.moduleListLink(page, null, ModuleFilters.none(page.module()), 1).path();
    }

    private static String datetime(long epochSecond) {
        return DATETIME.format(Instant.ofEpochSecond(epochSecond).atZone(ZONE));
    }

    /**
     * 旧分类缓存 {@code Type::getCache('type_list')}（Type::listData 的 'def' 格式，common/model/Type.php:35-86）：按 type_id 升序
     * 依次处理，type_extend 经 json_decode；顶级分类加 {@code childids}（直接子分类，逗号连接），子分类加 {@code type_1}（处理到它时上级的
     * 当前值，上级 id 更大时尚未加 childids，与旧版相同）。
     */
    static final class TypeObjects {

        private final Map<Integer, Map<String, Object>> objects = new LinkedHashMap<>();
        private final Map<Integer, List<Integer>> children = new HashMap<>();

        TypeObjects(List<LegacyTypeDto> types) {
            Map<Integer, Map<String, Object>> list = new LinkedHashMap<>();
            for (LegacyTypeDto t : types) {
                Map<String, Object> v = new LinkedHashMap<>(t.row().fields());
                v.put("type_extend", decode(t.row().string("type_extend")));
                list.put(t.id(), v);
                children.computeIfAbsent(t.parentId(), k -> new ArrayList<>()).add(t.id());
            }
            for (Map.Entry<Integer, Map<String, Object>> e : list.entrySet()) {
                Map<String, Object> v = e.getValue();
                int pid = ((Long) v.get("type_pid")).intValue();
                if (pid == 0) {
                    List<Integer> c = children.getOrDefault(e.getKey(), List.of());
                    v.put("childids", String.join(",", c.stream().map(String::valueOf).toList()));
                } else {
                    Map<String, Object> parent = list.get(pid);
                    v.put("type_1", parent == null ? null : new LinkedHashMap<>(parent));
                }
                objects.put(e.getKey(), v);
            }
        }

        boolean exists(long id) {
            return id > 0 && id <= Integer.MAX_VALUE && objects.containsKey((int) id);
        }

        Map<String, Object> object(int id) {
            return objects.get(id);
        }

        /**
         * ajax/data 的 tid 范围（Ajax.php:42-50）：顶级分类为 childids（不含它本身），否则为它本身。childids 为空时旧版是
         * {@code IN ('')}，MySQL 按 0 比较，所以等于 {@code IN (0)}。
         */
        List<Integer> scope(int id) {
            Map<String, Object> v = objects.get(id);
            if (((Long) v.get("type_pid")) == 0) {
                List<Integer> c = children.getOrDefault(id, List.of());
                return c.isEmpty() ? List.of(0) : c;
            }
            return List.of(id);
        }

        /** PHP {@code json_decode($s, true)} 后再由 json_encode 输出的值；不能解析时为 null。 */
        static Object decode(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            try {
                return convert(JSON.readTree(s));
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static Object convert(JsonNode n) {
            if (n == null || n.isNull()) {
                return null;
            }
            if (n.isString()) {
                return n.asString();
            }
            if (n.isBoolean()) {
                return n.asBoolean();
            }
            if (n.isIntegralNumber() && n.canConvertToLong()) {
                return n.asLong();
            }
            if (n.isNumber()) {
                return n.asDouble();
            }
            if (n.isArray()) {
                List<Object> out = new ArrayList<>();
                for (JsonNode x : n) {
                    out.add(convert(x));
                }
                return out;
            }
            // 对象：PHP 关联数组；键为 0..n-1 连续整数（含空对象）时 json_encode 输出数组
            Map<String, Object> m = new LinkedHashMap<>();
            boolean sequential = true;
            int i = 0;
            for (Iterator<Map.Entry<String, JsonNode>> it = n.properties().iterator(); it.hasNext(); i++) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getKey().equals(Integer.toString(i))) {
                    sequential = false;
                }
                m.put(e.getKey(), convert(e.getValue()));
            }
            return sequential ? new ArrayList<>(m.values()) : m;
        }
    }

    /** 不应发生：查询参数已由本类校验。 */
    static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
    }
}

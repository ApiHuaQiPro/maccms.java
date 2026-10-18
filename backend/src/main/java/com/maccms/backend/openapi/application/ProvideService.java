package com.maccms.backend.openapi.application;

import com.maccms.backend.common.clientip.IpAddress;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.content.application.LegacyColumnSet;
import com.maccms.backend.content.application.LegacyRowOrder;
import com.maccms.backend.content.application.LegacyRowQuery;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.content.application.view.LegacyTypeDto;
import com.maccms.backend.openapi.application.ProvideSettings.ModuleSettings;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Provide 资源站接口（B06，docs/architecture/16-b06-misc-pages-provide.md 第 3 节）：对齐旧 {@code api/controller/Provide.php}
 * 的 {@code vod/art/actor/role/website}（{@code ac=list/videolist/detail}，JSON/XML），{@code comment}、{@code index} 输出空响应。
 * 只读：{@code detail_inc_hits} 开启时也不写计数（B11）。
 *
 * <p>判定顺序：{@code status} → {@code charge} 白名单（客户端 IP 由 {@code common.clientip} 解析）→ 参数 → 查询 → 输出。
 */
public class ProvideService {

    /** 旧版时区 {@code PRC}（application/config.php:42）。 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,9}");
    /** ThinkPHP 5.0 PATH_INFO 参数（Route::parseUrlParams 的 {@code (\w+)\|([^\|]+)}）。 */
    private static final Pattern PATH_PAIR = Pattern.compile("(\\w+)\\|([^|]+)");
    private static final int MAX_PAGE = 1_000_000;

    private final Supplier<ProvideSettings> settings;
    private final LegacyRowReadService rows;
    private final Clock clock;

    /**
     * @param settings 旧配置（生产为启动时读出的固定值；按请求取一次，便于旧配置热加载（B15）与测试重放多组旧配置）
     */
    public ProvideService(Supplier<ProvideSettings> settings, LegacyRowReadService rows, Clock clock) {
        this.settings = settings;
        this.rows = rows;
        this.clock = clock;
    }

    /**
     * 合并后的请求参数（旧 {@code input()}：GET &lt; POST &lt; 路径参数，Request.php:658）。
     *
     * @param query    查询串参数（名 → 最后一个值，与 PHP {@code $_GET} 相同）
     * @param form     POST 表单参数（同上）
     * @param pathRest 操作名之后的路径段（已按 PATH_INFO 解码一次），如 {@code ["at","xml","ac","list"]}
     */
    public static Map<String, String> mergeParams(Map<String, String> query, Map<String, String> form,
                                                  List<String> pathRest) {
        Map<String, String> m = new LinkedHashMap<>(query);
        m.putAll(form);
        if (!pathRest.isEmpty()) {
            Matcher pm = PATH_PAIR.matcher(String.join("|", pathRest));
            while (pm.find()) {
                m.put(pm.group(1), pm.group(2));
            }
        }
        return m;
    }

    /**
     * @param action   操作名（{@code vod art actor role website comment index}）；其他值为 empty
     * @param params   {@link #mergeParams} 的结果（尚未按旧版 trim + urldecode）
     * @param clientIp 客户端 IP（{@code ClientIpResolver} 的结果）
     */
    public Optional<ProvideResponse> handle(String action, Map<String, String> params, String clientIp) {
        if (action.equals("comment") || action.equals("index")) {
            return Optional.of(ProvideResponse.empty());
        }
        Optional<ProvideModule> module = ProvideModule.byKey(action);
        if (module.isEmpty()) {
            return Optional.empty();
        }
        ProvideSettings current = settings.get();
        ModuleSettings ms = current.module(module.get());
        if (!ms.open()) {
            return Optional.of(ProvideResponse.closed());
        }
        if (ms.charge() && !allowed(ms, clientIp)) {
            return Optional.of(ProvideResponse.denied());
        }
        Map<String, String> p = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            Optional<String> v = PhpCompat.urldecode(PhpCompat.trim(e.getValue()));
            if (v.isEmpty()) {
                return Optional.of(ProvideResponse.badRequest());
            }
            p.put(e.getKey(), v.get());
        }
        try {
            return Optional.of(render(module.get(), current, ms, p));
        } catch (IllegalArgumentException e) {
            return Optional.of(ProvideResponse.badRequest());
        } catch (ApiException e) {
            if (e.code() == ErrorCode.SERVICE_UNAVAILABLE) {
                return Optional.of(ProvideResponse.unavailable());
            }
            return Optional.of(ProvideResponse.badRequest());
        }
    }

    /** 客户端 IP 未知（{@code 0.0.0.0}/{@code ::}）时一律不放行，即使白名单里配置了它。 */
    static boolean allowed(ModuleSettings ms, String clientIp) {
        return IpAddress.parse(clientIp).filter(a -> !a.isUnspecified()).map(ms.auth()::contains).orElse(false);
    }

    // ------------------------------------------------------------------ 查询

    private record Paging(Object pageJson, long page, Object limitJson, int limit) {
    }

    private ProvideResponse render(ProvideModule m, ProvideSettings settings, ModuleSettings ms, Map<String, String> p) {
        String ac = p.getOrDefault("ac", "");
        boolean full = m == ProvideModule.VOD ? ac.equals("videolist") || ac.equals("detail") : ac.equals("detail");
        boolean xml = m == ProvideModule.VOD && "xml".equals(p.get("at"));
        Instant now = clock.instant();

        List<String> ids = null;
        if (!PhpCompat.empty(p.get("ids"))) {
            ids = List.of(p.get("ids").split(",", -1));
            if (ids.size() > LegacyRowQuery.MAX_IDS) {
                throw new IllegalArgumentException("ids");
            }
        }
        String typeIdEquals = null;
        // typefilter 只在视频中限定行（Provide.php:48-50）；其余模块只影响 t 是否生效与分类表
        List<Integer> typeIdIn = m != ProvideModule.VOD || ms.typeFilter().isEmpty() ? null : ms.typeFilter();
        if (!PhpCompat.empty(p.get("t"))) {
            if (!m.table().typed()) {
                // 角色表没有 type_id：旧版 SQL 报错（Provide.php:594-598）
                throw new IllegalArgumentException("t");
            }
            String t = p.get("t");
            if (ms.typeFilterRaw().isEmpty() || ms.typeFilterRaw().contains(t)) {
                typeIdEquals = t;
                typeIdIn = null;
            }
        }
        Integer isEnd = null;
        if (m == ProvideModule.VOD && p.containsKey("isend")) {
            isEnd = PhpCompat.looseEquals(p.get("isend"), 1) ? 1 : 0;
        }
        Long timeAfter = null;
        Long timeBefore = null;
        String h = p.get("h");
        boolean hApplies = m == ProvideModule.VOD ? !PhpCompat.empty(h) : h != null && PhpIntval.of(h) != 0;
        if (hApplies) {
            if (!DIGITS.matcher(h).matches()) {
                throw new IllegalArgumentException("h");
            }
            long hours = Long.parseLong(h);
            ZonedDateTime local = now.atZone(ZONE);
            timeBefore = local.toLocalDate().plusDays(1).atStartOfDay(ZONE).toEpochSecond();
            Instant lower = now.minusSeconds(hours * 3600);
            timeAfter = m == ProvideModule.VOD ? lower.getEpochSecond()
                    : lower.atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toEpochSecond();
        }
        String wd = PhpCompat.empty(p.get("wd")) ? null : p.get("wd");
        List<String> years = null;
        if (m == ProvideModule.VOD && !PhpCompat.empty(p.get("year"))) {
            years = years(p.get("year"), now.atZone(ZONE).getYear());
        }
        String from = "";
        List<String> fromItems = List.of();
        List<String> playFromContains = null;
        if (m == ProvideModule.VOD) {
            from = ms.from();
            if (PhpCompat.empty(from) && !PhpCompat.empty(p.get("from")) && PhpCompat.byteLength(p.get("from")) > 2) {
                from = p.get("from");
            }
            if (!PhpCompat.empty(from)) {
                fromItems = fromItems(from);
                if (fromItems.size() > 20) {
                    throw new IllegalArgumentException("from");
                }
                if (!fromItems.isEmpty() && fromItems.stream().noneMatch(x -> PhpCompat.trim(x).isEmpty())) {
                    playFromContains = fromItems.stream().map(PhpCompat::trim).toList();
                }
            }
        }
        Paging paging = paging(m, ms, p);
        long offset = (long) paging.limit() * (paging.page() - 1);
        LegacyRowOrder order = m == ProvideModule.VOD && "asc".equals(p.get("sort_direction"))
                ? LegacyRowOrder.TIME_ASC : LegacyRowOrder.TIME_DESC;
        // 角色：旧 Role::listData 不使用 datafilter（Role.php:37-47）
        List<com.maccms.backend.content.application.LegacyCondition> conditions =
                m == ProvideModule.ROLE || ms.dataFilter().isEmpty() ? null : ms.dataFilter();
        LegacyRowQuery q = new LegacyRowQuery(m.table(), full ? LegacyColumnSet.FULL : LegacyColumnSet.LIST, order, ids,
                typeIdEquals, typeIdIn, null, timeAfter, timeBefore, wd, null, isEnd, years, playFromContains, conditions,
                offset, paging.limit());
        LegacyRowPageDto page = rows.read(q);
        List<LegacyTypeDto> types = m.table().typed() ? rows.types() : List.of();
        Map<Integer, LegacyTypeDto> typeById = new HashMap<>();
        for (LegacyTypeDto t : types) {
            typeById.put(t.id(), t);
        }
        Map<Long, LegacyRowDto> related = m == ProvideModule.ROLE ? relatedVideos(page) : Map.of();

        long pageCount = (page.total() + paging.limit() - 1) / paging.limit();
        String body;
        if (xml) {
            body = ProvideXml.vod(page, String.valueOf(paging.pageJson()), pageCount, String.valueOf(paging.limitJson()),
                    full, from, typeById, types, typeFilterSet(ms), x -> pic(settings, ms, x), this::datetime);
        } else {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 1L);
            out.put("msg", "数据列表");
            out.put("page", paging.pageJson());
            out.put("pagecount", pageCount);
            out.put("limit", paging.limitJson());
            out.put("total", page.total());
            List<Object> list = new ArrayList<>();
            for (LegacyRowDto row : page.rows()) {
                list.add(jsonRow(m, settings, ms, row, full, fromItems, typeById, related));
            }
            out.put("list", list);
            if (!full) {
                out.put("class", classList(m, types, ms));
            }
            body = LegacyJson.encode(out);
        }
        body = PhpCompat.filterTags(body);
        return new ProvideResponse(200, xml ? ProvideResponse.XML : ProvideResponse.JSON, cacheControl(ms),
                body.getBytes(StandardCharsets.UTF_8));
    }

    /** 旧 {@code pg}、{@code pagesize}（Provide.php:115-121、:391-393）。JSON 中 page/limit 保留旧版的字符串/整数类型。 */
    private static Paging paging(ProvideModule m, ModuleSettings ms, Map<String, String> p) {
        String pg = p.get("pg");
        Object pageJson = 1L;
        long page = 1;
        boolean defaulted = m == ProvideModule.VOD ? PhpCompat.empty(pg) : pg == null || PhpIntval.of(pg) == 0;
        if (!defaulted) {
            if (!DIGITS.matcher(pg).matches() || Long.parseLong(pg) > MAX_PAGE) {
                throw new IllegalArgumentException("pg");
            }
            page = Long.parseLong(pg);
            pageJson = pg;
        }
        Object limitJson = ms.pageSizeJson();
        int limit = ms.pageSize();
        String ps = p.get("pagesize");
        if (m == ProvideModule.VOD && !PhpCompat.empty(ps)) {
            if (!DIGITS.matcher(ps).matches()) {
                throw new IllegalArgumentException("pagesize");
            }
            long n = Long.parseLong(ps);
            if (n > 0) {
                limit = (int) Math.min(n, 100);
                limitJson = (long) limit;
            }
        }
        return new Paging(pageJson, page, limitJson, limit);
    }

    /** 旧 year（Provide.php:74-95）：4 字节单值、9 字节区间（夹在 1900..今年+3），其他长度为 {@code IN ('')}。 */
    static List<String> years(String raw, int currentYear) {
        int len = PhpCompat.byteLength(raw);
        if (len == 4) {
            return List.of(Long.toString(PhpIntval.of(raw)));
        }
        if (len == 9) {
            byte[] b = raw.getBytes(StandardCharsets.UTF_8);
            long start = PhpIntval.of(new String(b, 0, 4, StandardCharsets.ISO_8859_1));
            long end = PhpIntval.of(new String(b, 5, 4, StandardCharsets.ISO_8859_1));
            if (start > end) {
                long t = end;
                end = start;
                start = t;
            }
            start = Math.max(start, 1900);
            end = Math.min(end, currentYear + 3L);
            List<String> out = new ArrayList<>();
            for (long y = start; y <= end; y++) {
                out.add(Long.toString(y));
            }
            return out.isEmpty() ? List.of("") : out;
        }
        return List.of("");
    }

    /** {@code explode(',', trim($from))} → {@code array_unique} → {@code array_filter}（去掉 "" 与 "0"）。 */
    static List<String> fromItems(String from) {
        Set<String> unique = new LinkedHashSet<>(List.of(PhpCompat.trim(from).split(",", -1)));
        List<String> out = new ArrayList<>();
        for (String s : unique) {
            if (!PhpCompat.empty(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private Map<Long, LegacyRowDto> relatedVideos(LegacyRowPageDto page) {
        List<Long> ids = new ArrayList<>();
        for (LegacyRowDto r : page.rows()) {
            ids.add(r.number("role_rid"));
        }
        return rows.visibleVideos(ids, LegacyColumnSet.BRIEF);
    }

    // ------------------------------------------------------------------ JSON

    private Map<String, Object> jsonRow(ProvideModule m, ProvideSettings settings, ModuleSettings ms, LegacyRowDto row,
                                        boolean full,
                                        List<String> fromItems, Map<Integer, LegacyTypeDto> typeById,
                                        Map<Long, LegacyRowDto> related) {
        LegacyTable t = m.table();
        String pre = t.prefix();
        Map<String, Object> v = new LinkedHashMap<>(row.fields());
        if (t.typed()) {
            LegacyTypeDto type = typeById.get((int) row.number("type_id"));
            // 列表字段里有 "" as type_name（原位置）；整行时新增在末尾
            v.put("type_name", type == null ? null : type.row().get("type_name"));
        }
        v.put(pre + "_time", datetime(row.number(pre + "_time")));
        if (m == ProvideModule.ROLE) {
            LegacyRowDto vod = related.get(row.number("role_rid"));
            v.put("douban_id", vod == null ? null : vod.get("vod_douban_id"));
            v.put("vod_name", vod == null ? null : vod.get("vod_name"));
            v.put("vod_director", vod == null ? null : vod.get("vod_director"));
        }
        String picKey = pre + "_pic";
        if (v.containsKey(picKey)) {
            v.put(picKey, pic(settings, ms, row.string(picKey)));
        }
        if (m == ProvideModule.VOD) {
            String rawFrom = row.string("vod_play_from");
            if (full) {
                if (!fromItems.isEmpty()) {
                    List<String> arrFrom = List.of(rawFrom.split("\\$\\$\\$", -1));
                    List<String> urls = List.of(row.string("vod_play_url").split("\\$\\$\\$", -1));
                    List<String> servers = List.of(row.string("vod_play_server").split("\\$\\$\\$", -1));
                    List<String> notes = List.of(row.string("vod_play_note").split("\\$\\$\\$", -1));
                    List<String> keptFrom = new ArrayList<>();
                    List<String> keptUrl = new ArrayList<>();
                    List<String> keptServer = new ArrayList<>();
                    List<String> keptNote = new ArrayList<>();
                    for (String item : fromItems) {
                        int key = search(arrFrom, item);
                        if (key < 0) {
                            continue;
                        }
                        keptFrom.add(item);
                        keptUrl.add(at(urls, key));
                        keptServer.add(at(servers, key));
                        keptNote.add(at(notes, key));
                    }
                    v.put("vod_play_from", String.join(",", keptFrom));
                    v.put("vod_play_url", String.join("$$$", keptUrl));
                    v.put("vod_play_server", String.join("$$$", keptServer));
                    v.put("vod_play_note", String.join("$$$", keptNote));
                }
            } else if (!fromItems.isEmpty()) {
                List<String> arrFrom = List.of(rawFrom.split("\\$\\$\\$", -1));
                v.put("vod_play_from", String.join(",", fromItems.stream().filter(i -> search(arrFrom, i) >= 0).toList()));
            } else {
                v.put("vod_play_from", rawFrom.replace("$$$", ","));
            }
        }
        return v;
    }

    /** PHP {@code array_search}（非严格）：两边都是数字字符串时按数值比较。 */
    static int search(List<String> haystack, String needle) {
        for (int i = 0; i < haystack.size(); i++) {
            if (looseEqualsStrings(haystack.get(i), needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean looseEqualsStrings(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        Pattern num = Pattern.compile("[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
                + "[ \\t\\n\\r\\x0B\\f]*");
        if (num.matcher(a).matches() && num.matcher(b).matches()) {
            return new java.math.BigDecimal(a.strip()).compareTo(new java.math.BigDecimal(b.strip())) == 0;
        }
        return false;
    }

    private static String at(List<String> list, int i) {
        return i < list.size() ? list.get(i) : "";
    }

    private static List<Object> classList(ProvideModule m, List<LegacyTypeDto> types, ModuleSettings ms) {
        List<Object> out = new ArrayList<>();
        if (m == ProvideModule.ROLE) {
            return out;
        }
        Set<Integer> filter = typeFilterSet(ms);
        for (LegacyTypeDto t : types) {
            // 视频 JSON 的分类表不按 type_mid 过滤（Provide.php:251-261）；其余模块只列本模型
            if (!t.visible() || (m != ProvideModule.VOD && t.mid() != m.classMid())) {
                continue;
            }
            if (!filter.isEmpty() && !filter.contains(t.id())) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type_id", (long) t.id());
            c.put("type_pid", (long) t.parentId());
            c.put("type_name", t.row().get("type_name"));
            out.add(c);
        }
        return out;
    }

    static Set<Integer> typeFilterSet(ModuleSettings ms) {
        return Set.copyOf(ms.typeFilter());
    }

    // ------------------------------------------------------------------ 取值

    String datetime(long epochSecond) {
        return DATETIME.format(Instant.ofEpochSecond(epochSecond).atZone(ZONE));
    }

    /** 旧 getImgUrlProtocol（Provide.php:806-816）。 */
    static String picProtocol(ProvideSettings settings, ModuleSettings ms) {
        String def = (settings.uploadProtocol() == null ? "http" : settings.uploadProtocol()) + ":";
        if (ms.imgUrl() == null) {
            return def;
        }
        return ms.imgUrl().startsWith("https") ? "https:" : def;
    }

    /** 旧图片前缀（Provide.php:187-192）：{@code mac:} 换成协议（全部出现处）；其他非 http、非 // 的非空值前面加 imgurl。 */
    static String pic(ProvideSettings settings, ModuleSettings ms, String pic) {
        if (pic.startsWith("mac:")) {
            return pic.replace("mac:", picProtocol(settings, ms));
        }
        if (!PhpCompat.empty(pic) && !pic.startsWith("http") && !pic.startsWith("//")) {
            return (ms.imgUrl() == null ? "" : ms.imgUrl()) + pic;
        }
        return pic;
    }

    /** 16 文档 D-B06-4：不做服务端缓存，cachetime 只转成 HTTP 缓存头；charge=1 时 private。 */
    static String cacheControl(ModuleSettings ms) {
        if (ms.cacheTime() <= 0) {
            return ProvideResponse.NO_STORE;
        }
        return (ms.charge() ? "private" : "public") + ", max-age=" + ms.cacheTime();
    }

    static LocalDate today(Clock clock) {
        return clock.instant().atZone(ZONE).toLocalDate();
    }
}

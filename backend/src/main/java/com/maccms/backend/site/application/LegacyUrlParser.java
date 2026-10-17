package com.maccms.backend.site.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.VideoListQuery;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把旧站 URL 解析成 Java 承接的页面（{@link SiteRoute}）。只认明确支持的形态，规则见
 * docs/frontend/02-url-golden-vectors.md 第 4 节，逐条依据是 PHP 黄金向量中的解析结果。
 *
 * <p>三种结果：
 * <ul>
 * <li>{@code Optional.of(match)}：认得的形态；{@link Match#canonical()} 表示请求是否就是生成器的输出。</li>
 * <li>{@code Optional.empty()}：不认识（其他控制器、拼音/编码 id、别的后缀、大写、百分号编码的路径、当前配置下
 * 旧站会 404 的形态等），交给 PHP 处理（交还机制待部署评审，本地等同 404）。</li>
 * <li>抛 {@link ApiException}（INVALID_PARAMETER）：形态认得，但参数不合法——未知参数名、重复参数、参数不成对、
 * 查询串参数、id 或页码为 0、有前导 0、越界（页码 1..1000、分类 1..65535、视频 1..4294967295）。
 * site 把它当 404（SiteExceptionHandler）。</li>
 * </ul>
 * 输入是原始请求路径与查询串（未解码），不读取 Host、X-Forwarded-*、Forwarded 或请求协议。
 *
 * <p>搜索页（MACC-20）另用 {@link #parseSearch}：只认固定路径 + 查询串，参数不合法时搜索页显示 400（不是 404）。
 * {@link #parse} 仍不认识 {@code vod/search}。
 */
public final class LegacyUrlParser {

    private static final String ENTRY = "/index.php";
    private static final int MAX_INPUT = 1024;
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,20}");
    private static final Pattern ROUTE_TYPE = Pattern.compile("vodtype/([0-9]{1,20})(?:-([0-9]{1,20}))?");
    private static final Pattern ROUTE_DETAIL = Pattern.compile("voddetail/([0-9]{1,20})");
    /** 搜索页原始查询串的上限（只用于 {@link #parseSearch}；三页仍是 {@link #MAX_INPUT} 且拒绝查询串）。 */
    static final int MAX_SEARCH_QUERY = 2048;
    /** 搜索页的固定路径（PATHINFO 部分），对应 {@code .html}、{@code suffix_hide} 与无后缀三种。 */
    private static final List<String> SEARCH_PATHS = List.of("vod/search.html", "vod/search/", "vod/search");
    /**
     * 搜索页接受的查询串键（旧参数名）。B05 起承接 B04 的 actor/director/tag/letter/state/version/name/ids 与 tid
     * （docs/architecture/15-b05-readonly-pages.md 第 3.1 节）。
     */
    private static final Set<String> SEARCH_REQUEST_KEYS = Set.of("wd", "area", "lang", "year", "level", "class",
            "by", "order", "page", "actor", "director", "tag", "letter", "state", "version", "name", "ids", "tid", "ajax");
    /**
     * ThinkPHP 兼容模式 {@code s}（Request.php:403-406，它改变的是路由而不是条件）：B05 起值解码后是搜索页路径（{@link #SEARCH_PATHS}，
     * 可带一个开头的 /）时按搜索页处理，其余键照常；指向其他页面时不认（empty，交还 PHP）。{@code isend} 旧代码读不到
     * （15 文档第 3.1 节），按未知参数 400。{@code ajax}（旧 {@code intval}，只供模板使用）按队长裁定 D-B05-6 接受并忽略：仍检查重复与
     * 编码，不影响查询、不进链接/canonical/隐藏字段。
     */
    private static final String COMPAT_KEY = "s";
    /** vod/show 接受的键：搜索页的键去掉 tid（分类由 id 给出，D-B05-7），加上 id。 */
    private static final Set<String> SHOW_REQUEST_KEYS;
    /** {@code vodsearch/<…>} 的 14 个位置（application/route.php:331；14 文档第 2.3 节）。 */
    private static final List<String> SEARCH_SLOTS = List.of("wd", "actor", "area", "by", "class", "director", "lang",
            "letter", "level", "order", "page", "state", "tag", "year");
    /** {@code vodshow/<…>} 的 12 个位置（application/route.php:321）。 */
    private static final List<String> SHOW_SLOTS = List.of("id", "area", "by", "class", "lang", "letter", "level",
            "order", "page", "state", "tag", "year");

    static {
        Set<String> show = new HashSet<>(SEARCH_REQUEST_KEYS);
        show.remove("tid");
        show.add("id");
        SHOW_REQUEST_KEYS = Set.copyOf(show);
    }
    /** 按旧站 trim + {@code !empty()} 归一的文本键（application/common.php:1580-1604；Vod.php:167-247 只在非空时覆盖）。 */
    private static final List<String> SEARCH_TEXT_KEYS = List.of("wd", "area", "lang", "year", "class", "letter",
            "state", "version", "actor", "director", "tag", "name", "ids", "by", "order");

    private final LegacyUrlStyle style;
    private final LegacyUrlBuilder builder;

    public LegacyUrlParser(LegacyUrlBuilder builder) {
        this.builder = builder;
        this.style = builder.style();
    }

    /**
     * @param route     识别出的页面
     * @param path      该页面的规范站内路径（{@link LegacyUrlBuilder#path(SiteRoute)}）
     * @param canonical 请求是否正是规范地址（无查询串且路径相同）
     */
    public record Match(SiteRoute route, String path, boolean canonical) {
    }

    /**
     * @param rawPath  原始请求路径（如 {@code /index.php/vod/detail/id/1.html}），相对站点根
     * @param rawQuery 原始查询串（不含 ?），可为 null
     */
    public Optional<Match> parse(String rawPath, String rawQuery) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.length() > MAX_INPUT
                || (rawQuery != null && rawQuery.length() > MAX_INPUT) || rawPath.charAt(0) != '/'
                || !plainPath(rawPath.substring(1))) {
            return Optional.empty();
        }
        String query = rawQuery == null ? "" : rawQuery;
        String pathInfo;
        boolean compat = false;
        if (isEntry(rawPath)) {
            if (query.startsWith("s=") && query.indexOf('&') < 0) {
                // ?s= 兼容模式（Request.php:404-406）
                Optional<String> s = compatPathInfo(query.substring(2));
                if (s.isEmpty()) {
                    return Optional.empty();
                }
                pathInfo = s.get();
                compat = true;
            } else {
                pathInfo = "";
            }
        } else if (rawPath.startsWith(ENTRY + "/")) {
            pathInfo = rawPath.substring(ENTRY.length() + 1);
        } else if (style.rewrite()) {
            // 不含 index.php 的地址能否到达 PHP 取决于部署转发（假定整段路径作为 PATH_INFO）
            pathInfo = rawPath.substring(1);
        } else {
            return Optional.empty();
        }
        Optional<SiteRoute> route = route(pathInfo);
        if (route.isEmpty()) {
            return Optional.empty();
        }
        if (!compat && !query.isEmpty()) {
            // 旧代码里查询串参数会覆盖路径参数（mac_param_url 合并 $_REQUEST），也可能是未知参数：不猜
            throw ApiException.invalidParameter("query", "不支持查询串参数");
        }
        String path = builder.path(route.get());
        boolean canonical = query.isEmpty() && rawPath.equals("/" + path);
        return Optional.of(new Match(route.get(), path, canonical));
    }

    /**
     * SSR 搜索页（旧 {@code vod/search} 的固定路径 + 查询串形式），规则见 09 方案第 7.2.3 节，按其中的步骤顺序处理。
     *
     * <p>只认路径 {@code /index.php/vod/search.html}、{@code /index.php/vod/search/}、{@code /index.php/vod/search}
     * （{@code rewrite} 时另加去掉 {@code /index.php} 的三种）；路径参数形式、别名、{@code -} 分隔符、大写、其他后缀，
     * 查询串超过 {@link #MAX_SEARCH_QUERY}，含 {@code s} → empty（交 PHP）。
     * 未知键、重复键、值不能按表单规则解码一次为合法 UTF-8、页码不合法、条件不满足 content 的规则（{@link VideoFilter}、
     * {@link VideoListQuery}）或编码后超出链接预算 → INVALID_PARAMETER（搜索页显示 400，不回显输入）。值只解码一次，不做二次解码。
     *
     * <p>B05 逐键零值归一（队长裁定 Q2，15 文档第 3.2 节）：文本键去首尾 W 后 {@code ''}/{@code '0'} 为未传；{@code level}、
     * {@code tid} 按旧 {@code mac_param_url} 的 {@code intval}（{@link PhpIntval}），结果为 0 时未传，{@code tid} 为负数照常筛选；
     * {@code year} 为 {@code 0000}、{@code 0000-0000}（旧版 intval 后为空）时未传。{@code by=rnd} 为随机排序。
     */
    public Optional<Match> parseSearch(String rawPath, String rawQuery) {
        if (rawPath == null || rawPath.length() > MAX_INPUT) {
            return Optional.empty();
        }
        String query = rawQuery == null ? "" : rawQuery;
        if (query.length() > MAX_SEARCH_QUERY) {
            return Optional.empty();
        }
        boolean entry = isEntry(rawPath) && !style.routeAliases() && style.depr() == '/';
        if (!isSearchPath(rawPath) && !entry) {
            return Optional.empty();
        }
        List<String[]> items = queryItems(query);
        // 步骤 4：ThinkPHP 兼容模式 s（会覆盖路径，Request.php:403-406）：只认指向搜索页的值，其余交还 PHP；入口路径没有 s 时不是搜索页
        boolean compat = false;
        for (String[] kv : items) {
            if (kv[0].equals(COMPAT_KEY)) {
                if (compat) {
                    throw ApiException.invalidParameter(COMPAT_KEY, "参数重复");
                }
                Optional<String> s = formDecode(kv[1]);
                String v = s.map(x -> x.startsWith("/") ? x.substring(1) : x).orElse(null);
                if (v == null || !SEARCH_PATHS.contains(v)) {
                    return Optional.empty();
                }
                compat = true;
            }
        }
        if (entry && !compat) {
            return Optional.empty();
        }
        items.removeIf(kv -> kv[0].equals(COMPAT_KEY));
        Conditions c = conditions(decodeQuery(items, SEARCH_REQUEST_KEYS, new HashMap<>()));
        SiteRoute.VodSearch route = new SiteRoute.VodSearch(c.filters(), c.page());
        // 步骤 9：canonical = 路径是搜索基址且查询串与生成结果逐字相同
        String path = builder.path(route);
        boolean canonical = rawPath.equals("/" + path) && query.equals(builder.vodSearchLink(c.filters(), c.page()).query());
        return Optional.of(new Match(route, path, canonical));
    }

    /** 步骤 3：按 & 切分，跳过空项；键不解码，逐字比较。 */
    private static List<String[]> queryItems(String query) {
        List<String[]> items = new ArrayList<>();
        for (String item : query.split("&", -1)) {
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            items.add(eq < 0 ? new String[] {item, ""} : new String[] {item.substring(0, eq), item.substring(eq + 1)});
        }
        return items;
    }

    /**
     * 步骤 5：按出现顺序逐项检查查询串（未知键、重复键、表单解码），只报第一个错误；结果覆盖 {@code preset} 中的同名值
     * （旧 mac_param_url 的 {@code array_merge($input, $_REQUEST)}：查询串覆盖路径参数）。
     */
    private static Map<String, String> decodeQuery(List<String[]> items, Set<String> allowed,
                                                   Map<String, String> preset) {
        Map<String, String> decoded = new HashMap<>(preset);
        Set<String> seen = new HashSet<>();
        for (String[] kv : items) {
            String key = kv[0];
            if (!allowed.contains(key)) {
                throw ApiException.invalidParameter("query", "不支持的参数");
            }
            if (!seen.add(key)) {
                throw ApiException.invalidParameter(key, "参数重复");
            }
            decoded.put(key, formDecode(kv[1])
                    .orElseThrow(() -> ApiException.invalidParameter(key, "含不允许的字符")));
        }
        return decoded;
    }

    /** 已解码的条件与页码。 */
    private record Conditions(SearchFilters filters, int page) {
    }

    /** 步骤 7、8：旧站式归一与 content 的同一套校验（{@link #parseSearch} 与 {@link #parseVodList} 共用）。 */
    private static Conditions conditions(Map<String, String> decoded) {
        // 步骤 7：旧站式归一（文本键 trim + !empty()；level、tid 为 intval；year 的 intval 为 0 时不筛选），页码另按现有规则
        Map<String, String> text = new HashMap<>();
        for (String key : SEARCH_TEXT_KEYS) {
            text.put(key, normalized(decoded.get(key)));
        }
        if (text.get("year") != null && SearchFilters.yearIsZero(text.get("year"))) {
            text.put("year", null);
        }
        boolean random = "rnd".equals(text.get("by"));
        String by = random ? null : text.get("by");
        String order = text.get("order");
        String level = intvalNonZero(decoded.get("level"));
        String tidValue = intvalNonZero(decoded.get("tid"));
        String rawPage = decoded.get("page");
        int page = 1;
        if (rawPage != null && !rawPage.isEmpty()) {
            if (!DIGITS.matcher(rawPage).matches()) {
                throw ApiException.invalidParameter("page", pageRule());
            }
            page = (int) number("page", rawPage, VideoListQuery.MAX_PAGE);
        }
        // 步骤 8：交给 content 的同一套校验（既有参数在前，与 API 的顺序一致）；开关由 content 在查询时判断
        VideoListQuery sorting = VideoListQuery.parse(null, by, order, null, null);
        Map<String, String> apiNames = Map.of("keyword", "wd", "classes", "class");
        VideoFilter filter = VideoFilter.parse(p -> {
            String v = p.equals("level") ? level : text.get(apiNames.getOrDefault(p, p));
            return VideoFilter.RawParam.of(v);
        }, true);
        SearchFilters filters;
        try {
            filters = new SearchFilters(filter.keyword(), filter.area(), filter.lang(), filter.year(), filter.level(),
                    filter.classes(), filter.letter(), filter.state(), filter.version(), filter.actor(),
                    filter.director(), filter.tag(), filter.name(), filter.ids(),
                    tidValue == null ? null : Long.parseLong(tidValue), by == null ? null : sorting.sort(), random,
                    order == null ? null : sorting.descending() ? SearchFilters.SortOrder.DESC
                            : SearchFilters.SortOrder.ASC);
        } catch (IllegalArgumentException e) {
            // 各值都已满足 content 规则，只剩编码后超出链接预算（SearchFilters 第 4 条不变式）
            throw ApiException.invalidParameter("query", "搜索条件过长");
        }
        return new Conditions(filters, page);
    }

    /**
     * {@link #parseVodList} 的结果。
     *
     * @param route     {@link SiteRoute.VodSearch} 或 {@link SiteRoute.VodShow}
     * @param ajax      旧 {@code ajax_search} / {@code ajax_show}（只输出结果片段）
     * @param canonical 请求是否正是生成器的输出（位置参数、路径参数形式与 ajax 入口一律为否）
     */
    public record ListMatch(SiteRoute route, boolean ajax, boolean canonical) {
    }

    /**
     * 视频搜索/筛选页的全部入口（B05，15 文档第 3.6 节、队长裁定 D-B05-5/D-B05-7）。在 {@link #parseSearch}（固定路径 + 查询串、
     * 兼容模式 s）之外，另认：
     * <ul>
     * <li>{@code vod/ajax_search}：固定路径 + 查询串，与搜索页同一套键与规则，只输出结果片段；</li>
     * <li>{@code vodsearch/<14 位>}：旧路由的位置参数形式，先按 PATH_INFO 解码一次再按 {@code -} 切分，多出的段并入第 1 位
     * （{@code wd}），空位为未传；</li>
     * <li>{@code vod/show/id/<分类>[/键/值…]}、{@code vod/ajax_show/…}：旧 {@code mac_url_type(…,'show')} 的路径参数形式，键为
     * 搜索页的键去掉 {@code tid}（{@link #SHOW_REQUEST_KEYS}），值按 PATH_INFO 解码一次；</li>
     * <li>{@code vodshow/<12 位>}：位置参数形式，第 1 位为分类；多于 12 段时不认。</li>
     * </ul>
     * 查询串覆盖路径/位置中的同名值（旧 {@code array_merge($input, $_REQUEST)}），之后与搜索页同样归一、校验（步骤 5–8）。
     * 分类缺失、不是 1..65535 的十进制数字（含前导 0）→ empty（页面 404）；查询串含兼容模式 {@code s} → empty。
     * 只支持 site 的 URL 形态（分隔符 /、不启用路由别名）；位置参数形式只作为输入，canonical 指向查询串形式。
     */
    public Optional<ListMatch> parseVodList(String rawPath, String rawQuery) {
        if (rawPath == null || rawPath.length() > MAX_INPUT || style.routeAliases() || style.depr() != '/') {
            return Optional.empty();
        }
        String query = rawQuery == null ? "" : rawQuery;
        if (query.length() > MAX_SEARCH_QUERY) {
            return Optional.empty();
        }
        if (isSearchPath(rawPath) || isEntry(rawPath)) {
            return parseSearch(rawPath, rawQuery).map(m -> new ListMatch(m.route(), false, m.canonical()));
        }
        String pathInfo = rawPath.startsWith(ENTRY + "/") ? rawPath.substring(ENTRY.length() + 1)
                : style.rewrite() && rawPath.startsWith("/") ? rawPath.substring(1) : null;
        if (pathInfo == null) {
            return Optional.empty();
        }
        String p = pathInfo.endsWith(".html") ? pathInfo.substring(0, pathInfo.length() - ".html".length())
                : pathInfo.endsWith("/") ? pathInfo.substring(0, pathInfo.length() - 1) : pathInfo;
        List<String[]> items = queryItems(query);
        for (String[] kv : items) {
            if (kv[0].equals(COMPAT_KEY)) {
                return Optional.empty();
            }
        }
        Map<String, String> preset = new HashMap<>();
        boolean show;
        boolean ajax = false;
        if (p.equals("vod/ajax_search")) {
            show = false;
            ajax = true;
        } else if (p.startsWith("vodsearch/") || p.startsWith("vodshow/")) {
            show = p.startsWith("vodshow/");
            Optional<String> slots = pathDecode(p.substring(p.indexOf('/') + 1));
            if (slots.isEmpty() || slots.get().isEmpty() || slots.get().indexOf('/') >= 0) {
                return Optional.empty();
            }
            List<String> names = show ? SHOW_SLOTS : SEARCH_SLOTS;
            String[] parts = slots.get().split("-", -1);
            if (parts.length > names.size()) {
                if (show) {
                    return Optional.empty();
                }
                // 多出的段并入第 1 位（旧版 vodsearch/a-b-------------.html 得到 wd=a-b，14 文档第 2.3 节）
                int extra = parts.length - names.size();
                String[] merged = new String[names.size()];
                merged[0] = String.join("-", Arrays.copyOfRange(parts, 0, extra + 1));
                System.arraycopy(parts, extra + 1, merged, 1, names.size() - 1);
                parts = merged;
            }
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    preset.put(names.get(i), parts[i]);
                }
            }
        } else if (p.startsWith("vod/show/") || p.startsWith("vod/ajax_show/")) {
            show = true;
            ajax = p.startsWith("vod/ajax_show/");
            String[] seg = p.substring(p.indexOf('/', 4) + 1).split("/", -1);
            if (seg.length % 2 != 0) {
                throw ApiException.invalidParameter("path", "参数不成对");
            }
            for (int i = 0; i < seg.length; i += 2) {
                String key = seg[i];
                if (!SHOW_REQUEST_KEYS.contains(key)) {
                    throw ApiException.invalidParameter(key.isEmpty() || key.length() > 32 ? "path" : key, "不支持的参数");
                }
                String value = pathDecode(seg[i + 1])
                        .orElseThrow(() -> ApiException.invalidParameter(key, "含不允许的字符"));
                if (preset.put(key, value) != null) {
                    throw ApiException.invalidParameter(key, "参数重复");
                }
            }
        } else {
            return Optional.empty();
        }
        Map<String, String> decoded = decodeQuery(items, show ? SHOW_REQUEST_KEYS : SEARCH_REQUEST_KEYS, preset);
        if (!show) {
            Conditions c = conditions(decoded);
            return Optional.of(new ListMatch(new SiteRoute.VodSearch(c.filters(), c.page()), ajax, false));
        }
        String id = decoded.remove("id");
        if (id == null || !DIGITS.matcher(id).matches() || id.startsWith("0") || id.length() > 5
                || Integer.parseInt(id) > VideoListQuery.MAX_CATEGORY_ID) {
            return Optional.empty();
        }
        Conditions c = conditions(decoded);
        SiteRoute.VodShow route = new SiteRoute.VodShow(Integer.parseInt(id), c.filters(), c.page());
        SiteLink link = builder.vodShowLink(route.categoryId(), c.filters(), c.page());
        boolean canonical = !ajax && rawPath.equals("/" + link.path()) && query.equals(link.query());
        return Optional.of(new ListMatch(route, ajax, canonical));
    }

    private boolean isSearchPath(String rawPath) {
        if (style.routeAliases() || style.depr() != '/') {
            return false;
        }
        for (String p : SEARCH_PATHS) {
            if (rawPath.equals(ENTRY + "/" + p) || (style.rewrite() && rawPath.equals("/" + p))) {
                return true;
            }
        }
        return false;
    }

    /** 旧 {@code intval}（{@link PhpIntval}）；未出现或结果为 0 时为 {@code null}（未传），否则为十进制文本。 */
    private static String intvalNonZero(String v) {
        if (v == null) {
            return null;
        }
        long n = PhpIntval.of(v);
        return n == 0 ? null : Long.toString(n);
    }

    /** 去掉首尾 W 字符；结果为 {@code ''} 或 {@code '0'} 时按未传（{@code null}）。 */
    private static String normalized(String v) {
        if (v == null) {
            return null;
        }
        String s = SearchFilters.stripW(v);
        return s.isEmpty() || s.equals("0") ? null : s;
    }

    private static String pageRule() {
        return "必须是 1 到 " + VideoListQuery.MAX_PAGE + " 之间、没有前导 0 的整数";
    }

    private static boolean isEntry(String rawPath) {
        return rawPath.equals("/") || rawPath.equals(ENTRY) || rawPath.equals(ENTRY + "/");
    }

    /** ?s= 的值：按表单规则严格解码（UTF-8），去掉开头的一个 /（Request.php:419），其余须是本批认得的规范路径。 */
    private static Optional<String> compatPathInfo(String raw) {
        Optional<String> decoded = formDecode(raw);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        String v = decoded.get();
        if (v.startsWith("/")) {
            v = v.substring(1);
        }
        return plainPath(v) ? Optional.of(v) : Optional.empty();
    }

    /** 本批认得的路径只由小写字母、数字、. - _ / 组成，且没有空段、. 与 .. 段（末尾的 / 允许）。 */
    private static boolean plainPath(String p) {
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '/')) {
                return false;
            }
        }
        return SitePaths.isValid(p);
    }

    private Optional<SiteRoute> route(String pathInfo) {
        if (pathInfo.isEmpty()) {
            return Optional.of(new SiteRoute.Home());
        }
        String p;
        if (pathInfo.endsWith(".html")) {
            p = pathInfo.substring(0, pathInfo.length() - ".html".length());
        } else if (pathInfo.endsWith("/")) {
            p = pathInfo.substring(0, pathInfo.length() - 1);
        } else {
            p = pathInfo;
        }
        if (p.isEmpty() || p.indexOf('.') >= 0 || p.endsWith("/")) {
            return Optional.empty();
        }
        return style.routeAliases() ? routeAlias(p) : modulePath(p);
    }

    /**
     * route_status=1：只认 vodtype/&lt;id&gt;、vodtype/&lt;id&gt;-&lt;page&gt;、voddetail/&lt;id&gt;。
     * 模块路径 vod/type、vod/detail、index/index 在旧站此时直接 404（Route.php:1264-1273），不认。
     */
    private static Optional<SiteRoute> routeAlias(String p) {
        Matcher t = ROUTE_TYPE.matcher(p);
        if (t.matches()) {
            int id = (int) number("id", t.group(1), VideoListQuery.MAX_CATEGORY_ID);
            int page = t.group(2) == null ? 1 : (int) number("page", t.group(2), VideoListQuery.MAX_PAGE);
            return Optional.of(new SiteRoute.VodType(id, page));
        }
        Matcher d = ROUTE_DETAIL.matcher(p);
        if (d.matches()) {
            return Optional.of(new SiteRoute.VodDetail(number("id", d.group(1), VideoId.MAX)));
        }
        return Optional.empty();
    }

    /** 模块路径 控制器/操作/键/值…（Route.php:1217-1276）；"-" 分隔符下旧代码同时按 - 与 / 切分。 */
    private Optional<SiteRoute> modulePath(String p) {
        String[] seg = style.depr() == '-' ? p.split("[-/]", -1) : p.split("/", -1);
        for (String s : seg) {
            if (s.isEmpty()) {
                return Optional.empty();
            }
        }
        if (seg.length < 2) {
            return Optional.empty();
        }
        String target = seg[0] + "/" + seg[1];
        if (target.equals("index/index")) {
            return seg.length == 2 ? Optional.of(new SiteRoute.Home()) : Optional.empty();
        }
        LegacyPage legacyPage = LegacyPage.byTarget(target);
        if (legacyPage != null) {
            return legacyPage(legacyPage, seg);
        }
        if (!target.equals("vod/type") && !target.equals("vod/detail")) {
            return Optional.empty();
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 2; i < seg.length; i += 2) {
            if (i + 1 >= seg.length) {
                throw ApiException.invalidParameter("path", "参数不成对");
            }
            String key = seg[i];
            String value = seg[i + 1];
            boolean known = key.equals("id") || (key.equals("page") && target.equals("vod/type"));
            if (!known) {
                throw ApiException.invalidParameter(key.length() > 32 ? "path" : key, "不支持的参数");
            }
            if (!DIGITS.matcher(value).matches()) {
                // 非数字取值（拼音、编码 id、带后缀等）属于旧站，不认
                return Optional.empty();
            }
            if (params.put(key, value) != null) {
                throw ApiException.invalidParameter(key, "参数重复");
            }
        }
        if (!params.containsKey("id")) {
            throw ApiException.invalidParameter("id", "缺少 id");
        }
        if (target.equals("vod/detail")) {
            return Optional.of(new SiteRoute.VodDetail(number("id", params.get("id"), VideoId.MAX)));
        }
        int id = (int) number("id", params.get("id"), VideoListQuery.MAX_CATEGORY_ID);
        int page = params.containsKey("page") ? (int) number("page", params.get("page"), VideoListQuery.MAX_PAGE) : 1;
        return Optional.of(new SiteRoute.VodType(id, page));
    }

    /**
     * B05 页面（{@link LegacyPage}）的 键/值 段：只认该页面的参数名，值必须是十进制数字（拼音、编码 id 等非数字取值交还 PHP），
     * 重复、不成对、缺少必需参数（id；播放/下载入口的 sid、nid）或越界 → INVALID_PARAMETER（页面 404）。参数顺序不限，
     * 非生成器顺序时只是非规范地址（canonical 指向生成器的输出）。
     */
    private static Optional<SiteRoute> legacyPage(LegacyPage page, String[] seg) {
        Map<String, String> params = new HashMap<>();
        for (int i = 2; i < seg.length; i += 2) {
            if (i + 1 >= seg.length) {
                throw ApiException.invalidParameter("path", "参数不成对");
            }
            String key = seg[i];
            String value = seg[i + 1];
            if (!page.keys().contains(key)) {
                throw ApiException.invalidParameter(key.length() > 32 ? "path" : key, "不支持的参数");
            }
            if (!DIGITS.matcher(value).matches()) {
                return Optional.empty();
            }
            if (params.put(key, value) != null) {
                throw ApiException.invalidParameter(key, "参数重复");
            }
        }
        long id = 0;
        if (page.id() != LegacyPage.Id.NONE) {
            if (!params.containsKey("id")) {
                throw ApiException.invalidParameter("id", "缺少 id");
            }
            id = number("id", params.get("id"), page.id().max());
        }
        int sid = 0;
        int nid = 0;
        if (page.episode()) {
            if (!params.containsKey("sid") || !params.containsKey("nid")) {
                throw ApiException.invalidParameter("sid", "缺少播放源或集数");
            }
            sid = (int) number("sid", params.get("sid"), LegacyPage.MAX_SID);
            nid = (int) number("nid", params.get("nid"), LegacyPage.MAX_NID);
        }
        int pageNo = params.containsKey("page") ? (int) number("page", params.get("page"), VideoListQuery.MAX_PAGE) : 1;
        return Optional.of(new SiteRoute.Legacy(page, id, sid, nid, pageNo));
    }

    /** 十进制正整数，无前导 0，1..max；否则 INVALID_PARAMETER。 */
    private static long number(String field, String digits, long max) {
        if (digits.startsWith("0") || digits.length() > 10 || Long.parseLong(digits) > max) {
            throw ApiException.invalidParameter(field, "必须是 1 到 " + max + " 之间、没有前导 0 的整数");
        }
        return Long.parseLong(digits);
    }

    /** application/x-www-form-urlencoded 值的严格解码：+ 为空格，%HH 须完整，结果须是合法 UTF-8。 */
    private static Optional<String> formDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                out.write(' ');
            } else if (c == '%') {
                if (i + 2 >= s.length()) {
                    return Optional.empty();
                }
                int hi = hexDigit(s.charAt(i + 1));
                int lo = hexDigit(s.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    return Optional.empty();
                }
                out.write(hi * 16 + lo);
                i += 2;
            } else if (c > 0x20 && c < 0x7f) {
                out.write(c);
            } else {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /**
     * PATH_INFO 的严格解码（Web 服务器交给 PHP 之前做的那一次）：%HH 须完整，结果须是合法 UTF-8；{@code +} 原样；
     * 只接受可见 ASCII 原文。
     */
    private static Optional<String> pathDecode(String s) {
        return formDecode(s.replace("+", "%2B"));
    }

    /** %HH 的一位：只认 ASCII 0-9、A-F、a-f，否则 -1（Character.digit 还会认全角、阿拉伯-印度等 Unicode 数字）。 */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }
}

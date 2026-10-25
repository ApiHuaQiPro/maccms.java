package com.maccms.backend.site.application;

import com.maccms.backend.content.application.VideoListQuery;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 与旧 {@code mac_url}（application/common.php:1732-2428）及其调用的 ThinkPHP {@code url()}
 * （thinkphp/library/think/Url.php:28-163、:296-319）输出一致的 URL 生成器。不可变、无 I/O，不读取请求
 * （所以输出不受 Host、X-Forwarded-*、请求协议影响）。
 *
 * <p>输出是相对站点根的路径（不以 / 开头；旧站 {@code MAC_PATH='/'} 时在前面加 / 即为旧输出），可直接交给
 * {@link SiteSettings#canonical(String)} / {@link SiteSettings#publicPath(String)} 得到 {@link SafeUrl}。
 * 旧代码会产生不规范路径的输入（例如 {@code suffix_hide=1} 时关键词 {@code ..} 生成 {@code /../}）在这里抛
 * {@link IllegalArgumentException}，不输出。与 PHP 黄金向量的逐条对照见 docs/frontend/02-url-golden-vectors.md。
 *
 * <p>旧代码的生成结果与请求上下文有关的唯一情形（值里含 {@code index.php} 且经站点根访问时缺少 {@code /index.php}
 * 前缀，向量 {@code search.wd.indexphp}）按"经 /index.php 访问"的结果实现。
 */
public final class LegacyUrlBuilder {

    /** vod/search 可用的参数（application/route.php 的 vodsearch 变量，page 另行传入）。 */
    public static final List<String> SEARCH_KEYS = List.of("wd", "actor", "area", "by", "class", "director", "lang",
            "letter", "level", "order", "state", "tag", "year");
    /** vod/show 可用的筛选参数（application/route.php 的 vodshow 变量，id、page 另行传入）。 */
    public static final List<String> SHOW_KEYS = List.of("area", "by", "class", "lang", "letter", "level", "order",
            "state", "tag", "year");
    /**
     * Java SSR 搜索页链接的查询串键（MACC-20；B05 加入 B04 的新键与 tid），也是输出顺序（按键排序，与 mac_url 一致，
     * docs/architecture/15-b05-readonly-pages.md 第 3.3 节）。
     */
    public static final List<String> SEARCH_LINK_KEYS = List.of("actor", "area", "by", "class", "director", "ids",
            "lang", "letter", "level", "name", "order", "page", "state", "tag", "tid", "version", "wd", "year");
    /**
     * 模块筛选/搜索页（B05 D-B05-5，{@link #moduleListLink}）另用的查询串键：演员的 sex/starsign/blood、角色的 rid、专题的 id
     * （其余键与 {@link #SEARCH_LINK_KEYS} 相同）。
     */
    public static final List<String> MODULE_LINK_KEYS = List.of("blood", "id", "rid", "sex", "starsign");
    /**
     * 搜索链接查询串的最大长度：MACC-20 的 wd/area/lang/class 各取 content 的码点上限、每个码点 3 字节（U+FFFF 以上字符被拒绝）
     * 编码为 9 个字符，加上最长的 by、order、level、page 与 4 位年份（09 方案第 7.2.4 节）。B05 起这是预算而不是推论：
     * 新键会占用同一预算，超出预算的条件组合在 {@link SearchFilters} 中被拒绝（搜索页 400），所以生成的链接仍在 {@link SafeUrl} 上限内。
     */
    static final int MAX_SEARCH_LINK_QUERY = 1209;

    /** 仓库 application/route.php 中本批用到的路由；同名多条时按文件顺序尝试（Url::getRuleUrl）。 */
    private static final Map<String, List<String>> ROUTES = Map.of(
            "vod/type", List.of("vodtype/<id>-<page?>", "vodtype/<id>"),
            "vod/detail", List.of("voddetail/<id>"),
            "vod/play", List.of("vodplay/<id>-<sid>-<nid>"),
            "vod/show", List.of("vodshow/<id>-<area?>-<by?>-<class?>-<lang?>-<letter?>-<level?>-<order?>-<page?>"
                    + "-<state?>-<tag?>-<year?>"),
            "vod/search", List.of("vodsearch/<wd?>-<actor?>-<area?>-<by?>-<class?>-<director?>-<lang?>-<letter?>"
                    + "-<level?>-<order?>-<page?>-<state?>-<tag?>-<year?>"));
    private static final Pattern RULE_VAR = Pattern.compile("<(\\w+)(\\??)>");
    private static final Pattern USER_ACTION = Pattern.compile("[a-z_]{1,32}");

    private final LegacyUrlStyle style;

    public LegacyUrlBuilder(LegacyUrlStyle style) {
        if (style == null) {
            throw new IllegalArgumentException("缺少 URL 形态配置");
        }
        this.style = style;
    }

    public LegacyUrlStyle style() {
        return style;
    }

    /**
     * Java 承接页面的站内路径（canonical 用）。搜索页只返回固定路径（搜索基址），完整地址（含查询串）用
     * {@link #vodSearchLink}。
     */
    public String path(SiteRoute route) {
        return switch (route) {
            case SiteRoute.Home h -> home();
            case SiteRoute.VodType t -> vodType(t.typeId(), t.page());
            case SiteRoute.VodDetail d -> vodDetail(d.vodId());
            case SiteRoute.VodSearch s -> vodSearch(Map.of(), 1);
            case SiteRoute.VodShow s -> vodShowLink(s.categoryId(), SearchFilters.NONE, 1).path();
            case SiteRoute.ModuleList m -> moduleListLink(m.page(), m.categoryId(), ModuleFilters.none(m.page().module()),
                    1).path();
            case SiteRoute.Legacy l -> legacy(l);
        };
    }

    /**
     * B05 的"模块/操作 + 数字参数"页面（{@link LegacyPage}），参数按 {@link LegacyPage#keys()} 的顺序输出，页码 1 不输出。
     * 与 PHP 黄金向量 site/url-golden/b05-vectors.json 逐字一致（B05UrlGoldenVectorsTest）。
     */
    public String legacy(SiteRoute.Legacy route) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (String key : route.page().keys()) {
            switch (key) {
                case "id" -> vars.put("id", Long.toString(route.id()));
                case "sid" -> vars.put("sid", Integer.toString(route.sid()));
                case "nid" -> vars.put("nid", Integer.toString(route.nid()));
                case "page" -> vars.put("page", pageValue(route.pageNo()));
                default -> throw new IllegalStateException("未知参数");
            }
        }
        return macUrl(route.page().target(), vars, route.page().suffix());
    }

    /** 首页：旧模板用 {@code {$maccms.path}}，即 {@code MAC_PATH}（All.php:61），不经 mac_url。 */
    public String home() {
        return "";
    }

    /** 分类页，等价 {@code mac_url_type($info, ['page'=>$page])}；第 1 页不带页码。 */
    public String vodType(int typeId, int page) {
        new SiteRoute.VodType(typeId, page);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("id", Integer.toString(typeId));
        vars.put("page", pageValue(page));
        return macUrl("vod/type", vars);
    }

    /** 详情页，等价 {@code mac_url_vod_detail($info)}。 */
    public String vodDetail(long vodId) {
        new SiteRoute.VodDetail(vodId);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("id", Long.toString(vodId));
        return macUrl("vod/detail", vars);
    }

    /** 旧播放页（仍归 PHP），等价 {@code mac_url_vod_play($info, ['sid'=>..,'nid'=>..])}；sid/nid 须 ≥ 1。 */
    public String vodPlay(long vodId, int sid, int nid) {
        new SiteRoute.VodDetail(vodId);
        if (sid < 1 || nid < 1) {
            // 旧代码把小于 1 的值改成 1（common.php:2583-2588）；这里要求调用方给出真实的序号
            throw new IllegalArgumentException("播放源或集数序号越界");
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("id", Long.toString(vodId));
        vars.put("sid", Integer.toString(sid));
        vars.put("nid", Integer.toString(nid));
        return macUrl("vod/play", vars);
    }

    /** 旧搜索页（仍归 PHP），等价 {@code mac_url_search($filters + ['page'=>$page])}。 */
    public String vodSearch(Map<String, String> filters, int page) {
        Map<String, String> vars = filterVars(filters, SEARCH_KEYS, page);
        return macUrl("vod/search", vars);
    }

    /**
     * Java SSR 搜索页的链接（09 方案第 7.2.2 节）：固定路径 B = {@code vodSearch(Map.of(), 1)}（搜索基址，现有表单
     * {@code action}）+ 查询串 Q。Q 只含已设置的条件，键按 {@link #SEARCH_LINK_KEYS} 的顺序，页码只在 &gt; 1 时输出，
     * 值用 PHP {@code urlencode}（所以 {@code / \ % + & =} 都被编码，{@code .} 原样）。无条件第 1 页时没有查询串。
     *
     * <p>不用 {@link #vodSearch} 的 PATHINFO 形态：路径段承载不了 {@code /}、{@code \}、点段与 {@code suffix_hide} 下的
     * {@code .html}（第 7.2.1 节），而 {@link SitePaths} 的安全规则不能放宽。只支持 site 的 URL 形态（分隔符 /、不启用
     * 路由别名），否则抛 {@link IllegalStateException}。
     */
    public SiteLink vodSearchLink(SearchFilters filters, int page) {
        if (style.routeAliases() || style.depr() != '/') {
            throw new IllegalStateException("搜索页链接只支持 pathinfo_depr=/、不启用路由别名的形态");
        }
        new SiteRoute.VodSearch(filters, page);
        return new SiteLink(vodSearch(Map.of(), 1), searchQuery(filters, page));
    }

    /**
     * Java SSR 分类筛选页（旧 {@code vod/show}，B05 D-B05-7）的链接：固定路径 {@code vod/show/id/<分类>}（{@code mac_url} 的
     * 写法，与 {@link #vodShow} 无筛选条件时相同）+ 与 {@link #vodSearchLink} 相同的查询串（不含 tid）。旧版的路径参数形式
     * （{@code vod/show/area/…/id/1.html}）与 {@code vodshow/…} 位置参数形式只作为输入被认可（{@link LegacyUrlParser#parseVodList}）。
     */
    public SiteLink vodShowLink(int categoryId, SearchFilters filters, int page) {
        if (style.routeAliases() || style.depr() != '/') {
            throw new IllegalStateException("筛选页链接只支持 pathinfo_depr=/、不启用路由别名的形态");
        }
        new SiteRoute.VodShow(categoryId, filters, page);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("id", Integer.toString(categoryId));
        return new SiteLink(macUrl("vod/show", vars), searchQuery(filters, page));
    }

    /**
     * 文章、专题、演员、角色、网址、剧情的筛选/搜索页链接（B05 D-B05-5）：固定路径（搜索页 {@code <模块>/search}，筛选页
     * {@code <模块>/show/id/<分类>}，{@code mac_url} 的写法）+ 与视频搜索页相同写法的查询串。ajax 入口的链接指向完整页面。
     * 只支持 site 的 URL 形态（分隔符 /、不启用路由别名）。
     */
    public SiteLink moduleListLink(ModuleListPage page, Integer categoryId, ModuleFilters filters, int pageNo) {
        if (style.routeAliases() || style.depr() != '/') {
            throw new IllegalStateException("筛选/搜索页链接只支持 pathinfo_depr=/、不启用路由别名的形态");
        }
        ModuleListPage target = page.page();
        new SiteRoute.ModuleList(target, categoryId, filters, pageNo);
        Map<String, String> vars = new LinkedHashMap<>();
        if (categoryId != null) {
            vars.put("id", Integer.toString(categoryId));
        }
        return new SiteLink(macUrl(target.target(), vars), query(filters.params(), pageNo));
    }

    /** 搜索/筛选页链接的查询串：已设置的条件按键排序（mac_url 的 ksort），页码只在 &gt; 1 时输出，值用 PHP urlencode。 */
    private static String searchQuery(SearchFilters filters, int page) {
        return query(filters.params(), page);
    }

    private static String query(Map<String, String> params, int page) {
        Map<String, String> vars = new TreeMap<>(params);
        if (page > 1) {
            vars.put("page", Integer.toString(page));
        }
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (!q.isEmpty()) {
                q.append('&');
            }
            q.append(e.getKey()).append('=').append(urlencode(e.getValue()));
        }
        if (q.length() > MAX_SEARCH_LINK_QUERY) {
            // SearchFilters 的不变式保证不会发生（含 page=1000 的预算）；发生即是编码或上限被改动
            throw new IllegalStateException("搜索链接查询串超出上限");
        }
        return q.toString();
    }

    /** 旧筛选页（仍归 PHP），等价 {@code mac_url_type($info, $filters + ['page'=>$page], 'show')}。 */
    public String vodShow(int typeId, Map<String, String> filters, int page) {
        new SiteRoute.VodType(typeId, 1);
        Map<String, String> vars = filterVars(filters, SHOW_KEYS, page);
        vars.put("id", Integer.toString(typeId));
        return macUrl("vod/show", vars);
    }

    /** 会员页（B09 起由 Java 处理），等价 {@code mac_url('user/<action>')}。 */
    public String user(String action) {
        return user(action, Map.of());
    }

    /**
     * 带参数的会员页（B09：{@code findpass_msg} 的 {@code ac}），等价 {@code mac_url('user/<action>', $vars)}：参数按键排序、空值去掉
     * （{@code common.php:1734-1746}）。键须是小写字母与下划线，值须是小写字母。
     */
    public String user(String action, Map<String, String> vars) {
        if (action == null || !USER_ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("会员页操作名不合法");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (!USER_ACTION.matcher(e.getKey()).matches() || !e.getValue().matches("[a-z]{1,16}")) {
                throw new IllegalArgumentException("会员页参数不合法");
            }
            sorted.put(e.getKey(), e.getValue());
        }
        Map<String, String> all = new LinkedHashMap<>();
        all.put("page", "");
        all.putAll(sorted);
        return macUrl("user/" + action, all);
    }

    /**
     * 会员中心列表页的链接（B10，19 文档第 2 节），等价 ThinkPHP {@code url('user/<action>', ['page'=>$n] + 筛选)}：第 1 页不带页码；
     * 筛选（{@code mid}、{@code type}、{@code level} 等）的值是数字或小写字母，随分页保留（旧版不保留，D-B10-7）。
     */
    public String userPage(String action, Map<String, String> filters, int page) {
        if (action == null || !USER_ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("会员页操作名不合法");
        }
        if (page < 1 || page > 1_000_000) {
            throw new IllegalArgumentException("页码越界");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : filters.entrySet()) {
            if (!USER_ACTION.matcher(e.getKey()).matches() || !e.getValue().matches("[a-z0-9]{1,16}")) {
                throw new IllegalArgumentException("会员页参数不合法");
            }
            sorted.put(e.getKey(), e.getValue());
        }
        Map<String, String> all = new LinkedHashMap<>();
        all.put("page", pageValue(page));
        all.putAll(sorted);
        return macUrl("user/" + action, all);
    }

    /** B11 由 Java 承接的评论、留言与 ajax 接口（{@link #action} 只生成这些地址）。 */
    public static final List<String> ACTION_MODELS = List.of("comment/index", "comment/ajax", "comment/savedata",
            "comment/report", "comment/digg", "gbook/index", "gbook/ajax", "gbook/savedata", "gbook/report",
            "ajax/hits", "ajax/digg", "ajax/score", "ajax/verify_check");
    private static final Pattern ACTION_VALUE = Pattern.compile("[A-Za-z0-9_]{1,32}");

    /**
     * 评论、留言与 ajax 接口的地址（B11），等价 {@code mac_url('<模块>/<操作>', $vars)}：参数按键排序、空值与 {@code page=1} 去掉，按
     * ThinkPHP {@code /键/值} 追加在路径后。值只能是字母、数字与下划线（编号、类型）。
     */
    public String action(String model, Map<String, String> vars) {
        if (!ACTION_MODELS.contains(model)) {
            throw new IllegalArgumentException("不支持的地址");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (!USER_ACTION.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("参数名不合法");
            }
            String v = e.getValue();
            if (v == null || v.isEmpty() || v.equals("0") || (e.getKey().equals("page") && v.equals("1"))) {
                continue;
            }
            if (!ACTION_VALUE.matcher(v).matches()) {
                throw new IllegalArgumentException("参数值不合法");
            }
            sorted.put(e.getKey(), v);
        }
        return macUrl(model, new LinkedHashMap<>(sorted));
    }

    /** 旧 {@code mac_url_*($info)} 在 {@code $info} 为空时的地址（B10：会员记录里已删除的内容，只有模块/操作、没有编号）。 */
    public String bare(String model) {
        if (!List.of("vod/detail", "vod/type", "art/detail", "topic/detail", "actor/detail").contains(model)) {
            throw new IllegalArgumentException("不支持的地址");
        }
        return macUrl(model, new LinkedHashMap<>());
    }

    // ---- mac_url 的参数预处理（common.php:1734-1746）：去掉 empty() 的值，page=1 置空，按键排序 ----

    private static Map<String, String> filterVars(Map<String, String> filters, List<String> allowed, int page) {
        if (page < 1 || page > VideoListQuery.MAX_PAGE) {
            throw new IllegalArgumentException("页码越界");
        }
        Map<String, String> vars = new TreeMap<>();
        if (filters != null) {
            for (Map.Entry<String, String> e : filters.entrySet()) {
                if (!allowed.contains(e.getKey())) {
                    throw new IllegalArgumentException("不支持的参数");
                }
                String v = e.getValue();
                if (v == null || v.isEmpty() || v.equals("0")) {
                    continue;
                }
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(v)) {
                    throw new IllegalArgumentException("参数不是合法的 Unicode 文本");
                }
                vars.put(e.getKey(), v);
            }
        }
        vars.put("page", pageValue(page));
        return vars;
    }

    private static String pageValue(int page) {
        return page == 1 ? "" : Integer.toString(page);
    }

    // ---- mac_url 尾部处理（common.php:2376-2426，动态页分支，ENTRANCE=index） ----

    private String macUrl(String model, Map<String, String> vars) {
        return macUrl(model, vars, "html");
    }

    /** @param suffix ThinkPHP url() 的后缀（{@code html}；站点级 RSS 为 {@code xml}，common.php:1782-1793） */
    private String macUrl(String model, Map<String, String> vars, String suffix) {
        String url = thinkUrl(model, vars, suffix);
        if (style.rewrite()) {
            url = url.replace("index.php/", "");
        }
        if (style.suffixHide()) {
            url = url.replace(".html", "/");
            if (!model.contains("/show") && !model.contains("/search")) {
                url = replaceEach(url, "/", "-/", "_/", "-.", "_.");
            }
        } else if (!model.contains("search") && !model.contains("show")) {
            url = replaceEach(url, ".", "-.", "/.");
        }
        if (!url.startsWith("/")) {
            throw new IllegalStateException("生成结果不以 / 开头");
        }
        String sitePath = url.substring(1);
        if (!SitePaths.isValid(sitePath)) {
            throw new IllegalArgumentException("生成的地址不是规范路径");
        }
        return sitePath;
    }

    /** PHP str_replace(数组, 字符串, $s)：按顺序逐个替换。 */
    private static String replaceEach(String s, String replacement, String... searches) {
        for (String search : searches) {
            s = s.replace(search, replacement);
        }
        return s;
    }

    // ---- ThinkPHP url()：Url::build，Request root=/index.php，模块绑定 index（index.php:20） ----

    private String thinkUrl(String model, Map<String, String> vars, String suffix) {
        Map<String, String> rest = new LinkedHashMap<>(vars);
        String path = model;
        if (style.routeAliases() && ROUTES.containsKey(model)) {
            String routed = ruleUrl(ROUTES.get(model), rest);
            if (routed != null) {
                // Url.php:116-125 的模块绑定截断只作用于以 index 开头的地址，本批路由都不是
                path = routed;
            }
        }
        // 非路由时 parseUrl 得到 index/<model>，绑定截断后即 <model>
        String depr = String.valueOf(style.depr());
        StringBuilder url = new StringBuilder(path.replace("/", depr));
        for (Map.Entry<String, String> e : rest.entrySet()) {
            if (!phpTrim(e.getValue()).isEmpty()) {
                url.append(depr).append(e.getKey()).append(depr).append(urlencode(e.getValue()));
            }
        }
        url.append('.').append(suffix);
        String u = url.toString();
        int i = 0;
        while (i < u.length() && u.charAt(i) == '/') {
            i++;
        }
        return "/index.php/" + u.substring(i);
    }

    /** Url::getRuleUrl（Url.php:296-319）：命中的变量从 vars 中移除，其余追加在路径后。 */
    private static String ruleUrl(List<String> rules, Map<String, String> vars) {
        for (String rule : rules) {
            List<String[]> pattern = new ArrayList<>();
            Matcher m = RULE_VAR.matcher(rule);
            while (m.find()) {
                pattern.add(new String[] {m.group(1), m.group(2)});
            }
            String url = rule;
            String result = null;
            for (String[] p : pattern) {
                String key = p[0];
                boolean optional = !p[1].isEmpty();
                if (vars.containsKey(key)) {
                    String v = urlencode(vars.remove(key));
                    url = url.replace("[:" + key + "]", v).replace("<" + key + "?>", v).replace(":" + key, v)
                            .replace("<" + key + ">", v);
                    result = url;
                } else if (optional) {
                    url = url.replace("/[:" + key + "]", "").replace("[:" + key + "]", "").replace("<" + key + "?>", "");
                    result = url;
                } else {
                    break;
                }
            }
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /** PHP urlencode：字母数字与 -_. 原样，空格为 +，其余字节 %HH（大写）。 */
    static String urlencode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /** PHP trim 的默认字符集：空格、\t、\n、\r、\0、\x0B。 */
    private static String phpTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && " \t\n\r\0\u000B".indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && " \t\n\r\0\u000B".indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }
}

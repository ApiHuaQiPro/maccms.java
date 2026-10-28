package com.maccms.backend.staticgen.application;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.StaticSourceService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.view.StaticSourceItem;
import com.maccms.backend.content.application.view.StaticSourceTopic;
import com.maccms.backend.content.application.view.StaticSourceType;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.site.application.StaticPageSource;
import com.maccms.backend.site.application.StaticPageSource.Target;
import com.maccms.backend.staticgen.application.MakeOutput.Jump;
import com.maccms.backend.staticgen.application.StaticPaths.Item;
import com.maccms.backend.staticgen.application.StaticPaths.TypeRef;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 静态生成（旧 admin/controller/Make.php，B16，25 文档第 5 节）。后台"生成"各按钮与定时任务的 {@code make} 任务都走这里；
 * 两个入口的区别同旧版的 {@code ENTRANCE}：后台每次只做一批、结尾跳到下一批（{@link MakeOutput.Jump}），定时任务入口不跳转——分类页
 * 依次处理每个分类的前 {@code makesize} 页、内容页一次最多 999 条。
 *
 * <p>页面内容来自 {@link StaticPageSource}（与浏览器访问动态地址相同的正文），文件名来自 {@link StaticPaths}（与 PHP 逐字一致），
 * 写入经 {@link StaticFiles}。与旧版不同的地方（25 文档第 10 节）：页面取不到（不存在、未审核、关站）或文件名不在允许范围内时输出一行
 * "生成失败"、不写文件；RSS 只生成第 1 页；输出里的名称与地址做 HTML 转义。
 */
public final class MakeService {

    /** 入口（旧 {@code ENTRANCE}）。 */
    public enum Entrance {
        ADMIN, API
    }

    /** 旧站时区（application/config.php:42 {@code default_timezone => 'PRC'}）。 */
    public static final ZoneId LEGACY_ZONE = ZoneId.of("Asia/Shanghai");

    static final String STYLE = "<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}"
            + "span{font-weight:bold;color:#FF0000}</style>";
    private static final Set<String> RSS = Set.of("index", "baidu", "google", "so", "sogou", "bing", "sm");
    /** 新版分类页、专题首页每页条数（旧版取模板里分页标签的 num；新版页面固定每页这么多条）。 */
    static final int LIST_PAGE_SIZE = VideoListQuery.DEFAULT_PAGE_SIZE;

    private final LegacyConfigService config;
    private final Supplier<StaticSourceService> sources;
    private final Supplier<StaticPageSource> pages;
    private final StaticFiles files;
    private final LegacyLang lang;
    private final Clock clock;
    private final ZoneId zone;

    public MakeService(LegacyConfigService config, Supplier<StaticSourceService> sources, Supplier<StaticPageSource> pages,
                       StaticFiles files, LegacyLang lang, Clock clock, ZoneId zone) {
        this.config = config;
        this.sources = sources;
        this.pages = pages;
        this.files = files;
        this.lang = lang;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * 后台 {@code make/<动作>} 与定时任务的 {@code make} 任务。
     *
     * @param action  {@code make}（按 {@code ac} 分派，旧 Make::make）或直接的动作名（{@code index}、{@code map}、{@code rss} 等，后台菜单
     *                "生成首页""生成地图"直接请求这些动作）
     * @param params  请求参数（旧 {@code input()}；数组参数已按逗号连接）
     * @param referer 来源页（{@code ref=1} 时跳回），没有时为 null
     */
    public MakeOutput run(String action, Map<String, String> params, Entrance entrance, String referer) {
        StaticSourceService source = sources.get();
        if (source == null) {
            return new MakeOutput("", null, "数据库未配置，无法生成");
        }
        Run r = new Run(source, new LinkedHashMap<>(params), entrance, referer);
        try {
            if (action.equals("make")) {
                r.echo(STYLE);
                switch (r.p("ac")) {
                    case "index" -> r.index();
                    case "map" -> r.map();
                    case "rss" -> r.rss();
                    case "type" -> r.type();
                    case "topic_index" -> r.topicIndex();
                    case "topic_info" -> r.topicInfo();
                    case "info" -> r.info();
                    case "label" -> r.label();
                    default -> {
                    }
                }
            } else {
                switch (action) {
                    case "index" -> r.index();
                    case "map" -> r.map();
                    case "rss" -> r.rss();
                    case "type" -> r.type();
                    case "topic_index" -> r.topicIndex();
                    case "topic_info" -> r.topicInfo();
                    case "info" -> r.info();
                    case "label" -> r.label();
                    default -> throw new IllegalArgumentException("不支持的动作");
                }
            }
        } catch (Stop s) {
            // exit / die
        }
        return new MakeOutput(r.out.toString(), r.jump, r.error);
    }

    /** 旧 {@code exit}/{@code die}。 */
    private static final class Stop extends RuntimeException {
        Stop() {
            super(null, null, false, false);
        }
    }

    /** 一次请求的状态（旧 {@code $this->_param} 与输出缓冲）。 */
    private final class Run {
        final StaticSourceService source;
        final Map<String, String> param;
        final Entrance entrance;
        final String referer;
        final StringBuilder out = new StringBuilder();
        Jump jump;
        String error;
        final PhpArray maccms;
        final StaticPaths paths;
        Map<Long, StaticSourceType> typeCache;

        Run(StaticSourceService source, Map<String, String> param, Entrance entrance, String referer) {
            this.source = source;
            this.param = param;
            this.entrance = entrance;
            this.referer = referer;
            LegacyConfigService.Snapshot s = config.snapshotOrNull(LegacyConfigFile.MACCMS);
            this.maccms = s == null ? new PhpArray(Map.of()) : s.value();
            this.paths = new StaticPaths(maccms, zone);
        }

        String p(String key) {
            String v = param.get(key);
            return v == null ? "" : v;
        }

        long intval(String key) {
            return PhpIntval.of(p(key));
        }

        static boolean empty(String v) {
            return v == null || v.isEmpty() || v.equals("0");
        }

        long makesize() {
            long n = PhpIntval.of(PhpValues.text(maccms.path("app", "makesize")));
            return n < 1 ? 1 : n;
        }

        // ---- 输出 ----

        void echo(String html) {
            out.append(html).append("<br>");
        }

        /** 旧 {@code echoLink($des, $url, $color, $wrap)}。 */
        void link(String des, String url, boolean wrap) {
            String tail = wrap ? "<br>" : "&nbsp;";
            if (url == null || url.isEmpty()) {
                out.append("<font color=>").append(Html.escape(des)).append("</font>").append(tail);
            } else {
                out.append("<a target=\"_blank\" href=\"").append(Html.escape(url)).append("\"><font color=>")
                        .append(Html.escape(des)).append("</font></a>").append(tail);
            }
        }

        void link(String des) {
            link(des, "", true);
        }

        void failure(String file, String why) {
            out.append("<font color=red>").append(Html.escape("生成失败：" + file + "（" + why + "）")).append("</font><br>");
        }

        /** 旧 {@code mac_jump(url($action) . '?' . http_build_query(...), $sec)}，只在后台入口。 */
        void jumpTo(String action, Map<String, String> query, int sec) {
            if (entrance == Entrance.ADMIN) {
                jump = new Jump(action, query, sec, null);
            }
        }

        void jumpBack(int sec) {
            if (entrance == Entrance.ADMIN) {
                jump = new Jump(null, Map.of(), sec, referer);
            }
        }

        void fail(String key) {
            error = lang.get(key);
            throw new Stop();
        }

        void staticErr() {
            echo(Html.escape(lang.get("admin/make/view_model_static_err")));
            throw new Stop();
        }

        // ---- 生成一个文件 ----

        /** 旧 {@code buildHtml($link, './', 模板)}：页面取自动态地址，文件名按 reset_html_filename。 */
        boolean build(String link, Target target) {
            String file = paths.htmlFile(link);
            StaticPageSource src = pages.get();
            if (src == null) {
                failure(file, "前台未启用");
                return false;
            }
            StaticPageSource.Rendered page = src.render(target);
            if (!page.ok()) {
                failure(file, "页面状态 " + page.status());
                return false;
            }
            try {
                files.write(file, page.body());
                return true;
            } catch (StaticFiles.WriteFailure e) {
                failure(file, e.getMessage());
                return false;
            }
        }

        Map<Long, StaticSourceType> types() {
            if (typeCache == null) {
                typeCache = new LinkedHashMap<>();
                for (StaticSourceType t : source.types()) {
                    typeCache.put(t.id(), t);
                }
            }
            return typeCache;
        }

        TypeRef typeRef(StaticSourceType t) {
            return t == null ? null : new TypeRef(t.id(), t.en(), t.pid(), t.mid());
        }

        Item item(StaticSourceItem v) {
            StaticSourceType type = types().get(v.typeId());
            StaticSourceType parent = type == null ? null : types().get(type.pid());
            return new Item(v.id(), v.en(), v.time(), v.typeId(), typeRef(type), typeRef(parent));
        }

        long todayStart() {
            return LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toEpochSecond();
        }

        List<String> ids(String raw) {
            List<String> out = new ArrayList<>();
            for (String s : raw.split(",", -1)) {
                out.add(s);
            }
            return out;
        }

        void resetBatch() {
            param.put("start", "1");
            param.put("num", Long.toString(intval("num") + 1));
            param.put("data_count", "0");
            param.put("page_count", "0");
            param.put("page_size", "0");
        }

        // ---- 动作 ----

        void index() {
            echo(STYLE);
            boolean wap = p("ac2").equals("wap");
            String link = StaticPaths.index(wap);
            build(link, new StaticPageSource.Home(wap));
            link(link, "/" + link, true);
            jumpTo("make/opt", Map.of(), 3);
            throw new Stop();
        }

        void map() {
            echo(STYLE);
            String link = StaticPaths.map();
            build(link, new StaticPageSource.Sitemap());
            link(link, "/" + link, true);
            jumpTo("make/opt", Map.of(), 3);
            throw new Stop();
        }

        void rss() {
            echo(STYLE);
            String ac2 = p("ac2");
            if (!RSS.contains(ac2)) {
                fail("param_err");
            }
            long ps = intval("ps");
            if (ps == 0) {
                ps = 1;
            }
            for (int i = 1; i <= ps; i++) {
                String link = paths.rss(ac2, i);
                if (i == 1) {
                    build(link, new StaticPageSource.Rss(ac2));
                } else {
                    // 新版站点级 RSS 不分页（16 文档第 4.4 节），第 2 页起不生成（D-B16-6）
                    failure(link, "新版 RSS 不分页");
                }
                link(link, "/" + link, true);
            }
            jumpTo("make/opt", Map.of(), 3);
            throw new Stop();
        }

        void type() {
            while (true) {
                String tab = p("tab");
                String ids = tab.equals("art") ? p("arttype") : p("vodtype");
                if (empty(ids) && p("ac2").equals("day")) {
                    ids = String.join(",", source.typeIdsSince(tab.equals("art") ? "art" : "vod", todayStart())
                            .stream().map(String::valueOf).toList());
                }
                if (paths.viewMode(tab + "_type") < 2) {
                    staticErr();
                }
                long num = intval("num");
                long start = intval("start");
                long pageCount = intval("page_count");
                long dataCount = intval("data_count");
                if (empty(ids)) {
                    fail("param_err");
                }
                List<String> list = ids(ids);
                if (num >= list.size()) {
                    if (empty(p("jump"))) {
                        link(lang.get("admin/make/typepage_make_complete"));
                        jumpTo("make/opt", Map.of(), 3);
                    } else {
                        link(lang.get("admin/make/typepage_make_complete_later_make_index"));
                        jumpTo("make/index", Map.of("jump", "1"), 3);
                    }
                    throw new Stop();
                }
                if (start < 1) {
                    start = 1;
                }
                long id = PhpIntval.of(list.get((int) num));
                StaticSourceType type = types().get(id);
                String typeName = type == null ? "" : type.name();
                if (dataCount == 0) {
                    dataCount = type == null ? 0 : source.countInType(tab.equals("art") ? "art" : "vod", id);
                    long pageSize = LIST_PAGE_SIZE;
                    pageCount = ceil(dataCount, pageSize);
                    if (pageCount < 1) {
                        pageCount = 1;
                    }
                    param.put("data_count", Long.toString(dataCount));
                    param.put("page_count", Long.toString(pageCount));
                    param.put("page_size", Long.toString(pageSize));
                }
                if (start > pageCount) {
                    resetBatch();
                    link("【" + typeName + "】" + lang.get("admin/make/list_make_complate_later"));
                    jumpTo("make/make", new LinkedHashMap<>(param), 3);
                    throw new Stop();
                }
                long makesize = makesize();
                link(TimmingService.format(lang.get("admin/make/type_tip"), typeName, p("page_count"),
                        Long.toString(ceil(pageCount, makesize)), Long.toString(ceil(start, makesize))));
                int n = 1;
                for (long i = start; i <= pageCount; i++) {
                    param.put("start", Long.toString(i));
                    if (type == null) {
                        failure("", "分类不存在");
                        break;
                    }
                    String link;
                    try {
                        link = paths.type(tab.equals("art") ? "art" : "vod", typeRef(type), (int) i);
                    } catch (IllegalStateException e) {
                        staticErr();
                        return;
                    }
                    Target t = tab.equals("art") ? new StaticPageSource.ArtType((int) id, (int) i)
                            : new StaticPageSource.VodType((int) id, (int) i);
                    build(link, t);
                    link(lang.get("the") + i + lang.get("page"), link, true);
                    if (makesize == n) {
                        break;
                    }
                    n++;
                }
                if (entrance == Entrance.API) {
                    if (num + 1 >= list.size()) {
                        echo(Html.escape(TimmingService.format(lang.get("admin/make/type_timming_tip"),
                                Long.toString(makesize))));
                        throw new Stop();
                    }
                    resetBatch();
                    continue;
                }
                if (intval("start") >= intval("page_count")) {
                    resetBatch();
                    link("【" + typeName + "】" + lang.get("admin/make/list_make_complate_later"));
                } else {
                    param.put("start", Long.toString(intval("start") + 1));
                    link(lang.get("server_rest"));
                }
                jumpTo("make/make", new LinkedHashMap<>(param), 3);
                return;
            }
        }

        void topicIndex() {
            long start = intval("start");
            long pageCount = intval("page_count");
            long dataCount = intval("data_count");
            if (start < 1) {
                start = 1;
            }
            long makesize = 1;
            if (paths.viewMode("topic_index") < 2) {
                staticErr();
            }
            if (dataCount == 0) {
                dataCount = source.countTopics();
                pageCount = ceil(dataCount, LIST_PAGE_SIZE);
                if (pageCount < 1) {
                    pageCount = 1;
                }
                param.put("start", Long.toString(start));
                param.put("data_count", Long.toString(dataCount));
                param.put("page_count", Long.toString(pageCount));
                param.put("page_size", Long.toString(LIST_PAGE_SIZE));
            }
            if (start > pageCount) {
                link(lang.get("admin/make/topicpage_make_complete"));
                jumpTo("make/opt", Map.of(), 3);
                throw new Stop();
            }
            link(TimmingService.format(lang.get("admin/make/topic_index_tip"), p("page_count"),
                    Long.toString(ceil(pageCount, makesize)), Long.toString(ceil(start, makesize))));
            for (long i = start; i <= pageCount; i++) {
                param.put("start", Long.toString(i));
                String link;
                try {
                    link = paths.topicIndex((int) i);
                } catch (IllegalStateException e) {
                    staticErr();
                    return;
                }
                build(link, new StaticPageSource.TopicIndex((int) i));
                link(lang.get("the") + i + lang.get("page"), link, true);
                break;
            }
            if (intval("start") >= pageCount) {
                link(lang.get("admin/make/topicpage_make_complete"));
                jumpTo("make/opt", Map.of(), 3);
                throw new Stop();
            }
            param.put("start", Long.toString(intval("start") + 1));
            link(lang.get("server_rest"));
            jumpTo("make/make", new LinkedHashMap<>(param), 3);
        }

        void topicInfo() {
            String raw = p("topic");
            if (empty(raw)) {
                fail("param_err");
            }
            List<String> list = ids(raw);
            if (paths.viewMode("topic_detail") < 2) {
                staticErr();
            }
            link(TimmingService.format(lang.get("admin/make/topic_tip"), Integer.toString(list.size())));
            List<Long> update = new ArrayList<>();
            for (String a : list) {
                Long id = parseId(a);
                if (id == null) {
                    continue;
                }
                update.add(id);
                StaticSourceTopic t = source.topic(id);
                if (t == null) {
                    continue;
                }
                String link;
                try {
                    link = paths.topicDetail(new StaticPaths.Topic(t.id(), t.en()));
                } catch (IllegalStateException e) {
                    staticErr();
                    return;
                }
                build(link, new StaticPageSource.TopicDetail(t.id()));
                link(t.name(), link, true);
            }
            markMade("topic", update);
            if (p("ref").equals("1") && referer != null && !referer.isEmpty()) {
                jumpBack(2);
                throw new Stop();
            }
            link(lang.get("admin/make/topic_make_complete"));
            jumpTo("make/opt", Map.of(), 3);
        }

        void info() {
            String tab = p("tab").equals("art") ? "art" : "vod";
            String typeIds;
            if (p("tab").equals("art")) {
                typeIds = p("arttype");
                if (paths.viewMode("art_detail") < 2) {
                    staticErr();
                }
            } else {
                typeIds = p("vodtype");
                if (paths.viewMode("vod_detail") < 2 && paths.viewMode("vod_play") < 2 && paths.viewMode("vod_down") < 2) {
                    staticErr();
                }
            }
            String ids = p("ids");
            long num = intval("num");
            long start = intval("start");
            long pageCount = intval("page_count");
            long dataCount = intval("data_count");
            if (start < 1) {
                start = 1;
            }
            if (pageCount < 1) {
                pageCount = 1;
            }
            if (empty(ids) && empty(typeIds) && empty(p("ac2"))) {
                fail("param_err");
            }
            String typeName = "";
            Long typeId = null;
            List<Long> idList = null;
            if (!empty(typeIds)) {
                List<String> list = ids(typeIds);
                if (num >= list.size()) {
                    if (empty(p("jump"))) {
                        link(lang.get("admin/make/info_make_complete") + "1");
                        jumpTo("make/opt", Map.of(), 3);
                    } else {
                        link(lang.get("admin/make/info_make_complete_later_make_type"));
                        Map<String, String> q = new LinkedHashMap<>();
                        q.put("jump", "1");
                        q.put("ac", "type");
                        q.put("tab", p("tab"));
                        q.put(p("tab") + "type", String.join(",", list));
                        q.put("ac2", "day");
                        jumpTo("make/make", q, 3);
                    }
                    throw new Stop();
                }
                typeId = PhpIntval.of(list.get((int) num));
                StaticSourceType type = types().get(typeId);
                typeName = type == null ? "" : type.name();
            } else if (!empty(ids)) {
                typeName = lang.get("select_data");
                if (start > pageCount) {
                    echo(Html.escape(lang.get("admin/make/info_make_complete") + "2"));
                    throw new Stop();
                }
                idList = new ArrayList<>();
                for (String s : ids(ids)) {
                    Long id = parseId(s);
                    if (id != null) {
                        idList.add(id);
                    }
                }
                if (idList.isEmpty()) {
                    idList.add(0L);
                }
            }
            Long since = null;
            boolean notMade = false;
            if (p("ac2").equals("day")) {
                typeName = typeName + lang.get("today_data");
                since = todayStart();
            } else if (p("ac2").equals("nomake")) {
                typeName = lang.get("no_make_data");
                start = 1;
                dataCount = 0;
                notMade = true;
            }
            long makesize = entrance == Entrance.API ? 999 : makesize();
            StaticSourceService.Filter filter = new StaticSourceService.Filter(typeId, idList, since, notMade);
            if (dataCount == 0) {
                dataCount = source.count(tab, filter);
                pageCount = ceil(dataCount, makesize);
                param.put("data_count", Long.toString(dataCount));
                param.put("page_count", Long.toString(pageCount));
                param.put("page_size", Long.toString(makesize));
            }
            if (start > pageCount) {
                link("【" + typeName + "】" + lang.get("admin/make/info_make_complete_later"));
                if (p("ac2").equals("nomake")) {
                    jumpTo("make/opt", Map.of(), 3);
                    throw new Stop();
                }
                resetBatch();
                jumpTo("make/make", new LinkedHashMap<>(param), 3);
                throw new Stop();
            }
            link(TimmingService.format(lang.get("admin/make/info_tip"), typeName, p("data_count"), p("page_count"),
                    p("page_size"), Long.toString(start)));
            List<StaticSourceItem> rows = source.list(tab, filter, start, (int) Math.min(makesize, Integer.MAX_VALUE));
            List<Long> update = new ArrayList<>();
            PhpArray players = tab.equals("vod") ? config.snapshotOrNull(LegacyConfigFile.VODPLAYER) == null ? null
                    : config.snapshotOrNull(LegacyConfigFile.VODPLAYER).value() : null;
            PhpArray downers = tab.equals("vod") ? config.snapshotOrNull(LegacyConfigFile.VODDOWNER) == null ? null
                    : config.snapshotOrNull(LegacyConfigFile.VODDOWNER).value() : null;
            for (StaticSourceItem v : rows) {
                Item item = item(v);
                if (tab.equals("art")) {
                    out.append(Html.escape(substring(v.name(), 100))).append("&nbsp;");
                    int total = v.content().isEmpty() || v.content().equals("0") ? 0
                            : v.content().split("\\$\\$\\$", -1).length;
                    for (int i = 1; i <= total; i++) {
                        String link = paths.artDetail(item, i);
                        build(link, new StaticPageSource.ArtDetail(v.id(), i));
                        if (i == 1) {
                            link("detail", link, true);
                        }
                    }
                    update.add(v.id());
                    continue;
                }
                out.append(Html.escape(v.name())).append("&nbsp;");
                if (paths.viewMode("vod_detail") == 2) {
                    String link = paths.vodDetail(item);
                    build(link, new StaticPageSource.VodDetail(v.id()));
                    link("detail", link, false);
                }
                update.add(v.id());
                for (String f : List.of("play", "down")) {
                    long mode = paths.viewMode("vod_" + f);
                    boolean down = f.equals("down");
                    Map<Integer, Integer> sources = down ? EpisodeCounts.of(v.downFrom(), v.downUrl(), downers)
                            : EpisodeCounts.of(v.playFrom(), v.playUrl(), players);
                    // 旧版只在 vod_play_from 非空时算出列表与总数（Make.php:781-788），否则总数未定义、循环 0 次
                    int total = sources.size();
                    if (mode == 2) {
                        String link = paths.vodEpisode(f, item, 1, 1);
                        build(link, new StaticPageSource.VodEpisode(down, v.id(), 1, 1));
                        link(f, link, false);
                    } else if (mode == 3) {
                        for (int i = 1; i <= total; i++) {
                            int count = sources.getOrDefault(i, 0);
                            for (int j = 1; j <= count; j++) {
                                String link = paths.vodEpisode(f, item, i, j);
                                build(link.split("\\?", 2)[0], new StaticPageSource.VodEpisode(down, v.id(), i, j));
                                if (i == 1 && j == 1) {
                                    link(f + "-" + i + "-" + j, link, false);
                                }
                            }
                        }
                    } else if (mode == 4) {
                        for (int i = 1; i <= total; i++) {
                            String link = paths.vodEpisode(f, item, i, 1);
                            build(link.split("\\?", 2)[0], new StaticPageSource.VodEpisode(down, v.id(), i, 1));
                            if (i == 1) {
                                link(f + "-" + i, link, false);
                            }
                        }
                    }
                }
                out.append("<br>");
            }
            boolean marked = markMade(tab, update);
            if (p("ref").equals("1") && referer != null && !referer.isEmpty()) {
                jumpBack(2);
                throw new Stop();
            }
            if (!marked && p("ac2").equals("nomake")) {
                // 可写数据源关闭时"未生成"的条件永远成立，继续跳转会一直重复同一批（新版在这里停下，D-B16-8）
                jumpTo("make/opt", Map.of(), 3);
                throw new Stop();
            }
            if (start > pageCount) {
                resetBatch();
                link("【" + typeName + "】" + lang.get("admin/make/info_make_complete_later"));
            } else {
                param.put("start", Long.toString(start + 1));
                link(lang.get("server_rest"));
            }
            jumpTo("make/make", new LinkedHashMap<>(param), 3);
        }

        void label() {
            String raw = p("label");
            if (empty(raw)) {
                fail("param_err");
            }
            raw = raw.replace('\\', '/');
            if (raw.split("\\./", -1).length > 1) {
                error = lang.get("param_err") + "2";
                throw new Stop();
            }
            List<String> list = ids(raw);
            link(TimmingService.format(lang.get("admin/make/label_tip"), Integer.toString(list.size())));
            for (String a : list) {
                String fullname = a.split("\\.", -1)[0];
                String file = StaticPaths.label(a);
                build(file, new StaticPageSource.Label(fullname));
                link(file, "/" + file, true);
            }
            link(lang.get("admin/make/label_complete"));
            jumpTo("make/opt", Map.of(), 3);
        }

        /** 回写已生成时间；可写数据源关闭时输出一行提示并返回 false。 */
        boolean markMade(String tab, List<Long> ids) {
            if (ids.isEmpty()) {
                return true;
            }
            if (source.markMade(tab, ids, clock.instant().getEpochSecond()) < 0) {
                out.append("<font color=red>").append(Html.escape("可写数据源未开启，未记录生成时间")).append("</font><br>");
                return false;
            }
            return true;
        }
    }

    static long ceil(long a, long b) {
        return b <= 0 ? 0 : (a + b - 1) / b;
    }

    /** 旧 {@code mac_substring($s, 100)}：按字符截取。 */
    static String substring(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    private static Long parseId(String s) {
        String t = s.strip();
        if (!t.matches("[0-9]{1,10}")) {
            return null;
        }
        return Long.parseLong(t);
    }
}

package com.maccms.backend.staticgen.application;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.StaticSourceService;
import com.maccms.backend.content.application.UrlsendSourceService;
import com.maccms.backend.content.application.view.UrlsendItem;
import com.maccms.backend.integration.application.UrlPush;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * URL 推送（E09，B28；30 文档第 2、3 节）：旧 {@code admin/controller/Urlsend.php} 的 {@code data()} 与 {@code push()}，后台
 * {@code urlsend/push}、{@code urlsend/data} 与定时任务 {@code urlsend}（旧 {@code Timming::urlsend} 调同一个 {@code push}）共用。
 *
 * <ul>
 * <li>{@code data}：按 {@code mid}（1 视频、2 文章、3 专题、8 演员、9 角色、11 网址）、{@code ac2=today}（当天，{@code range=1} 时按新增时间）、
 * {@code ids}、{@code page}、{@code limit}（默认 50）取已审核的行，逐行输出"编号、名称 地址"，地址是
 * {@code http(s):// + site_url + 详情页路径}；</li>
 * <li>{@code push}：把本页地址交给 {@link UrlPush}；失败输出旧版提示并停止；最后一页输出"数据推送完毕"；否则后台跳到下一页
 * （{@link Output#next}），定时任务在同一个请求里接着推下一页。</li>
 * </ul>
 * 输出的 HTML 与旧版 {@code mac_echo} 逐行相同，名称、地址与百度返回的提示按 HTML 转义（D-B28-4）。取不到数据时只输出"没有获取到数据"，
 * 不再像旧版那样把空列表推给百度（D-B28-3）。不写库。
 */
public final class UrlsendService {

    /** 调用入口：后台一页一个请求（跳转），定时任务一次推完。 */
    public enum Entrance { ADMIN, API }

    /**
     * @param html  已输出的内容
     * @param next  后台要跳到的下一页的参数（旧 {@code mac_jump(url('urlsend/push') . '?' . http_build_query($this->_param), 3)}），没有时为 null
     * @param error 旧 {@code $this->error(...)} 的提示（推送方式不存在），没有时为 null；有时 {@code html} 为空
     */
    public record Output(String html, Map<String, String> next, String error) {
    }

    static final String STYLE = "<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}"
            + "span{font-weight:bold;color:#FF0000}</style>";
    /** 每页条数的上限（旧版不限；一次推送的地址数与内存占用有界，D-B28-5）。 */
    static final int MAX_LIMIT = 10_000;
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");
    private static final Pattern NUMERIC = Pattern.compile("[ \\t\\n\\r\\x0B\\f]*[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    private final LegacyConfigService legacy;
    private final Supplier<UrlsendSourceService> source;
    private final Supplier<StaticSourceService> types;
    private final Supplier<UrlPush> push;
    private final LegacyLang lang;
    private final Clock clock;
    private final ZoneId zone;

    public UrlsendService(LegacyConfigService legacy, Supplier<UrlsendSourceService> source, Supplier<StaticSourceService> types,
                          Supplier<UrlPush> push, LegacyLang lang, Clock clock, ZoneId zone) {
        this.legacy = legacy;
        this.source = source;
        this.types = types;
        this.push = push;
        this.lang = lang;
        this.clock = clock;
        this.zone = zone;
    }

    /** 后台 {@code urlsend/data}：只列出本页要推送的地址（旧版直接访问时在列表之后报错，D-B28-7）。 */
    public String data(Map<String, String> param, String httpType) {
        StringBuilder out = new StringBuilder();
        data(new LinkedHashMap<>(param), httpType, out);
        return out.toString();
    }

    /**
     * 旧 {@code Urlsend::push($pp)}。
     *
     * @param param    旧 {@code input()}（后台）或定时任务参数的 {@code parse_str} 结果，按原顺序
     * @param httpType {@code http://} 或 {@code https://}（旧 {@code $GLOBALS['http_type']}，本次请求的协议）
     */
    public Output push(Map<String, String> param, String httpType, Entrance entrance) {
        String ac = param.getOrDefault("ac", "");
        UrlPush p = push.get();
        if (!UrlPush.supports(ac) || p == null) {
            return new Output("", null, lang.get("param_err"));
        }
        Map<String, String> q = new LinkedHashMap<>(param);
        StringBuilder out = new StringBuilder();
        while (true) {
            Page page = data(q, httpType, out);
            if (page == null) {
                return new Output(out.toString(), null, null);
            }
            UrlPush.Result r = p.submit(ac, page.site(), page.urls());
            if (!r.ok()) {
                line(out, r.msg());
                return new Output(out.toString(), null, null);
            }
            if (page.page() >= page.pageCount()) {
                line(out, lang.get("admin/urlsend/complete"));
                return new Output(out.toString(), null, null);
            }
            q.put("page", Long.toString(page.page() + 1));
            if (entrance == Entrance.ADMIN) {
                return new Output(out.toString(), q, null);
            }
        }
    }

    /** 本页：站点（{@code http_type . site_url}）、地址、页码与页数。 */
    record Page(String site, List<String> urls, long page, long pageCount) {
    }

    /**
     * 旧 {@code data()}：输出样式、提示与本页各行，返回本页；取不到数据、参数不合法或地址形态不支持时输出提示并返回 null。
     * 与旧版一样把规范化后的 {@code page}、{@code limit} 写回参数（后台跳转的下一页地址里带的是规范化后的值）。
     */
    Page data(Map<String, String> q, String httpType, StringBuilder out) {
        out.append(STYLE).append("<br>");
        long page = PhpIntval.of(q.get("page")) < 1 ? 1 : PhpIntval.of(q.get("page"));
        long limit = PhpIntval.of(q.get("limit")) < 1 ? 50 : PhpIntval.of(q.get("limit"));
        q.put("page", Long.toString(page));
        q.put("limit", Long.toString(limit));
        int mid = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, PhpIntval.of(q.get("mid"))));
        List<Long> ids = ids(q.get("ids"));
        if (page > UrlsendSourceService.MAX_PAGE || limit > MAX_LIMIT || ids == null) {
            line(out, lang.get("param_err"));
            return null;
        }
        UrlsendSourceService src = source.get();
        if (src == null) {
            line(out, lang.get("admin/urlsend/no_data"));
            return null;
        }
        Long since = "today".equals(q.get("ac2")) ? LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toEpochSecond() : null;
        UrlsendSourceService.Filter f = new UrlsendSourceService.Filter(mid, since, looseEqualsOne(q.get("range")),
                ids.isEmpty() ? null : ids);
        List<UrlsendItem> rows = src.page(f, page, (int) limit);
        if (rows.isEmpty()) {
            line(out, lang.get("admin/urlsend/no_data"));
            return null;
        }
        long total = src.count(f);
        long pageCount = (total + limit - 1) / limit;
        PhpArray maccms = maccms();
        String site = httpType + text(maccms, "site", "site_url");
        List<String> urls = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        try {
            StaticSourceService t = types.get();
            UrlsendLinks links = UrlsendLinks.of(maccms, zone, () -> t == null ? List.of() : t.types());
            for (UrlsendItem it : rows) {
                String url = site + links.path(mid, it);
                urls.add(url);
                String u = Html.escape(url);
                lines.append(it.id()).append('、').append(Html.escape(it.name())).append("&nbsp;<a href=\"").append(u)
                        .append("\">").append(u).append("</a><br>");
            }
        } catch (UrlsendLinks.Unsupported e) {
            line(out, e.getMessage());
            return null;
        }
        line(out, sprintf(lang.get("admin/urlsend/tip"), Long.toString(total), Long.toString(pageCount), Long.toString(page)));
        out.append(lines);
        return new Page(site, urls, page, pageCount);
    }

    private PhpArray maccms() {
        if (!legacy.isConfigured()) {
            return null;
        }
        LegacyConfigService.Snapshot s = legacy.snapshotOrNull(LegacyConfigFile.MACCMS);
        return s == null ? null : s.value();
    }

    /**
     * 旧 {@code if(!empty($ids)) $where[...] = ['in', $ids]}：空串与 {@code "0"} 不筛选（空列表）；逗号分隔，空项跳过，其余每项必须是十进制
     * 数字（旧版交给 MySQL 隐式转换，D-B13-4 同一规则），否则 null。
     */
    static List<Long> ids(String raw) {
        List<Long> out = new ArrayList<>();
        if (raw == null || raw.isEmpty() || raw.equals("0")) {
            return out;
        }
        for (String s : raw.split(",", -1)) {
            if (s.isEmpty()) {
                continue;
            }
            if (!DIGITS.matcher(s).matches()) {
                return null;
            }
            out.add(Long.parseLong(s));
        }
        return out;
    }

    /** 旧 {@code $this->_param['range'] == '1'}：两边都是数字串时按数值比较。 */
    static boolean looseEqualsOne(String v) {
        if (v == null) {
            return false;
        }
        if (v.equals("1")) {
            return true;
        }
        if (!NUMERIC.matcher(v).matches()) {
            return false;
        }
        try {
            return new java.math.BigDecimal(v.strip()).compareTo(java.math.BigDecimal.ONE) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 旧 {@code lang($key, [$a, $b, ...])}：依次替换 {@code %s}。 */
    static String sprintf(String format, String... args) {
        StringBuilder sb = new StringBuilder();
        int from = 0;
        int n = 0;
        while (true) {
            int i = format.indexOf("%s", from);
            if (i < 0 || n >= args.length) {
                break;
            }
            sb.append(format, from, i).append(args[n++]);
            from = i + 2;
        }
        return sb.append(format.substring(from)).toString();
    }

    private static void line(StringBuilder out, String text) {
        out.append(Html.escape(text)).append("<br>");
    }

    private static String text(PhpArray a, String... path) {
        return a == null ? "" : PhpValues.text(a.path(path));
    }
}

package com.maccms.backend.collect.application;

import com.maccms.backend.content.application.PhpJson;
import com.maccms.backend.content.application.PhpText;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义规则的执行（B20，28 文档第 3 节）：旧 {@code application/common/util/Collection.php} 的 {@code url_list}、{@code get_url_lists}、
 * {@code get_content} 逐步对应，页面内容来自本地 HTML（{@link Pages}），不发请求（E03 只做本地版本）。
 *
 * <p><b>字节文字</b>：旧代码的字符串操作（{@code explode}、{@code str_replace}、{@code trim}、不带 {@code u} 的 PCRE）都按字节进行。本类的
 * 文字一律是"一个字符一个字节"的 ISO-8859-1 字符串（{@link #bytes}、{@link #utf8}），所以 GBK 页面转码后、或规则里的中文与页面里的中文
 * 都按 UTF-8 字节比较，结果与旧版逐字节相同。
 *
 * <p><b>PHP 7.3 与 8.0</b>：旧 {@code get_content} 第 0 页结尾用 {@code preg_replace_callback(…, array('collection', …), …)} 把正文里的
 * 相对图片地址换成绝对地址，但类名 {@code collection} 不带命名空间、并不存在：PHP 8 抛出 TypeError，内容采集整页 500；PHP 7.3 告警后原样
 * 返回正文（null 转成空串）。新版按 7.3：正文不改写图片地址（D-B20-3）。规则为空串时 {@code explode('')} 在 PHP 8 抛 ValueError，7.3 得到
 * 空结果，新版按 7.3（D-B20-4）。{@code strpos($s, '')}（下一页规则为空）在 PHP 8 为 0、7.3 为 false，新版按 7.3（不跟随任何链接）。
 *
 * <p>一个实例对应旧版的一次请求：分页时访问过的地址（旧 {@code static $oldurl}）在同一实例的多次 {@link #content} 之间累积。不是线程安全的。
 */
public final class CjRules {

    /** 序列网址最多展开的个数（旧版没有上限；页码递增为 0 时旧版死循环）。 */
    public static final int MAX_URLS = 10_000;
    /** 一条"过滤规则"（一组 {@code preg_replace}）的匹配时限。 */
    static final Duration DEFAULT_REGEX_BUDGET = Duration.ofSeconds(2);
    /** 一次 {@link #content}（含它的分页）或 {@link #urlLists} 最多读取的页面数（旧版只靠 {@code $oldurl} 防止循环，"全部列出"模式没有上限）。 */
    public static final int MAX_PAGES = 100;

    private static final String MARK = utf8("[内容]");
    private static final String SPLIT = "[|]";
    private static final Pattern LIST_LINK = Pattern.compile("<a ([^>]*)>([^/a>].*)</a>", Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);
    private static final Pattern HREF = Pattern.compile("href=['\"]?([^'\" ]*)['\"]?", Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);
    private static final Pattern NEXT_LINK = Pattern.compile("<a [^>]*href=['\"]?([^>'\" ]*)['\"]?[^>]*>([^</]*)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);
    private static final Pattern ALL_LINK = Pattern.compile("<a [^>]*href=['\"]?([^>'\" ]*)['\"]?", Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);

    /** 本地 HTML：按地址（字节文字）取页面的原始字节；没有时 null（旧 {@code mac_curl_get} 失败）。 */
    @FunctionalInterface
    public interface Pages {
        byte[] fetch(String url);
    }

    /** 规则无法执行（旧版报错或无法结束），{@code reason} 是给管理员看的说明。 */
    public static final class RuleException extends RuntimeException {
        private final String reason;

        RuleException(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** 列表页里的一条内容网址（字节文字）。 */
    public record Link(String url, String title) {
    }

    private final Map<String, String> node;
    private final Pages pages;
    private final LongSupplier now;
    private final Duration regexBudget;
    private final List<String> oldurl = new ArrayList<>();
    private int fetched;

    /**
     * @param node  {@code mac_cj_node} 的一行（列名 → 库里的文字）
     * @param now   旧 {@code time()}
     */
    public CjRules(Map<String, String> node, Pages pages, LongSupplier now) {
        this(node, pages, now, DEFAULT_REGEX_BUDGET);
    }

    CjRules(Map<String, String> node, Pages pages, LongSupplier now, Duration regexBudget) {
        this.node = node;
        this.pages = pages;
        this.now = now;
        this.regexBudget = regexBudget;
    }

    private String cfg(String k) {
        String v = node.get(k);
        return v == null ? "" : utf8(v);
    }

    // ------------------------------------------------------------------ url_list

    /** 旧 {@code url_list($config)}：要采集的列表页地址（字节文字）。 */
    public List<String> urlList() {
        List<String> url = new ArrayList<>();
        String type = cfg("sourcetype");
        String page = cfg("urlpage");
        if (loose(type, "1")) {
            long start = number(cfg("pagesize_start"));
            long num = number(cfg("pagesize_end"));
            long step = number(cfg("par_num"));
            if (num < start) {
                num = start;
            }
            if (step <= 0) {
                throw new RuleException("页码递增必须大于 0（旧版在此死循环）");
            }
            if ((num - start) / step + 1 > MAX_URLS) {
                throw new RuleException("序列网址超过 " + MAX_URLS + " 个，请缩小页码范围");
            }
            for (long i = start; i <= num; i += step) {
                url.add(page.replace("(*)", Long.toString(i)));
            }
        } else if (loose(type, "2")) {
            url.addAll(IngestPhp.explode("\r\n", page));
        } else if (loose(type, "3") || loose(type, "4")) {
            url.add(page);
        }
        return url;
    }

    // ------------------------------------------------------------------ get_url_lists

    /** 旧 {@code get_url_lists($url, $config)}：没有取到页面时 null（旧版 false）；否则按 {@code preg_match_all} 的序号保留键。 */
    public LinkedHashMap<Long, Link> urlLists(String url) {
        fetched = 0;
        String html = html(url);
        if (html == null) {
            return null;
        }
        if (loose(cfg("sourcetype"), "4")) {
            throw new RuleException("RSS 网址类型在旧版不可用（旧版缺少 pc_base 类，报错）");
        }
        String cut = cutHtml(html, cfg("url_start"), cfg("url_end"));
        String h = cut == null ? "" : cut.replace("\r", "").replace("\n", "");
        h = h.replace("</a>", "</a>\n").replace("</A>", "</a>\n");
        LinkedHashMap<Long, Link> data = new LinkedHashMap<>();
        Matcher m = LIST_LINK.matcher(h);
        long k = 0;
        String contain = cfg("url_contain");
        String except = cfg("url_except");
        while (m.find()) {
            Matcher href = HREF.matcher(m.group(1));
            if (href.find()) {
                String u = href.group(1);
                if (truthy(contain) && !u.contains(contain) || truthy(except) && u.contains(except)) {
                    k++;
                    continue;
                }
                data.put(k, new Link(urlCheck(u, url), stripTags(m.group(2))));
            }
            k++;
        }
        return data;
    }

    // ------------------------------------------------------------------ get_content

    /** 旧 {@code get_content($url, $config)}：没有取到页面时 null；否则按旧版的键顺序（标题、分类、时间、自定义项、正文）。 */
    public LinkedHashMap<Object, Object> content(String url) {
        fetched = 0;
        return content(url, 0);
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<Object, Object> content(String url, int page) {
        String html = html(url);
        if (html == null) {
            return null;
        }
        LinkedHashMap<Object, Object> data = null;
        if (page == 0) {
            data = new LinkedHashMap<>();
            if (truthy(cfg("title_rule"))) {
                data.put("title", extract(html, cfg("title_rule"), cfg("title_html_rule")));
            }
            if (truthy(cfg("type_rule"))) {
                data.put("type", extract(html, cfg("type_rule"), cfg("type_html_rule")));
            }
            data.put("time", now.getAsLong());
            Object diy = PhpJson.decode(node.get("customize_config"));
            Iterable<Object> items = diy instanceof Map<?, ?> mm ? (Iterable<Object>) mm.values() : diy instanceof List<?> l ? (List<Object>) l : List.of();
            for (Object v : items) {
                if (!(v instanceof Map<?, ?> item)) {
                    continue;
                }
                Object rule = item.get("rule");
                if (!(rule instanceof String r) || !truthy(r)) {
                    continue;
                }
                Object html2 = item.get("html_rule");
                Object en = item.get("en_name");
                if (html2 != null && !(html2 instanceof String) || en != null && !(en instanceof String)) {
                    continue;
                }
                Object key = ProvideValues.key(en == null ? "" : (String) en);
                data.put(key, extract(html, utf8(r), html2 == null ? "" : utf8((String) html2)));
            }
        }
        if (truthy(cfg("content_rule"))) {
            if (data == null) {
                data = new LinkedHashMap<>();
            }
            data.put("content", extract(html, cfg("content_rule"), cfg("content_html_rule")));
        }
        if ((page == 0 || page == 2) && !empty(cfg("content_page_start")) && !empty(cfg("content_page_end"))) {
            oldurl.add(url);
            List<Object> tmp = new ArrayList<>();
            tmp.add(data == null ? null : data.get("content"));
            String pageHtml = cutHtml(html, cfg("content_page_start"), cfg("content_page_end"));
            if (loose(cfg("content_page_rule"), "2") && truthy(pageHtml)) {
                Matcher m = NEXT_LINK.matcher(pageHtml);
                String next = cfg("content_nextpage");
                while (m.find()) {
                    // strpos($v, '') 在 PHP 7.3 是 false（告警），不跟随
                    if (next.isEmpty() || !m.group(2).contains(next)) {
                        continue;
                    }
                    if (loose(m.group(1), "#")) {
                        continue;
                    }
                    String u = urlCheck(m.group(1), url);
                    if (inArray(u, oldurl)) {
                        continue;
                    }
                    oldurl.add(u);
                    LinkedHashMap<Object, Object> r = content(u, 2);
                    Object c = r == null ? null : r.get("content");
                    if (!inArray(c, tmp)) {
                        tmp.add(c);
                    }
                }
            }
            if (loose(cfg("content_page_rule"), "1") && page == 0 && truthy(pageHtml)) {
                Matcher m = ALL_LINK.matcher(pageHtml);
                LinkedHashMap<Long, String> uniq = new LinkedHashMap<>();
                long k = 0;
                while (m.find()) {
                    String u = m.group(1);
                    if (!uniq.containsValue(u)) {
                        uniq.put(k, u);
                    }
                    k++;
                }
                String second = uniq.get(1L);
                for (Map.Entry<Long, String> e : uniq.entrySet()) {
                    // 旧代码写成 $out[1][$k]：数组第 1 项（字符串）的第 k 个字节
                    long i = e.getKey();
                    if (second != null && i < second.length() && second.charAt((int) i) == '#') {
                        continue;
                    }
                    String u = urlCheck(e.getValue(), url);
                    LinkedHashMap<Object, Object> r = content(u, 1);
                    Object c = r == null ? null : r.get("content");
                    if (!inArray(c, tmp) && !looseEq(c, "")) {
                        tmp.add(c);
                    }
                }
            }
            if (data == null) {
                data = new LinkedHashMap<>();
            }
            List<String> parts = new ArrayList<>();
            for (Object t : tmp) {
                parts.add(phpString(t));
            }
            data.put("content", String.join(loose(cfg("content_page"), "1") ? "[page]" : "", parts));
        }
        if (page == 0) {
            // PHP 7.3：回调无效，preg_replace_callback 原样返回正文（转成字符串）
            data.put("content", phpString(data.get("content")));
        }
        return data;
    }

    /** 标题、分类、正文、自定义项共用：规则不含 {@code [内容]} 时就是规则本身，否则切取后过滤。 */
    private Object extract(String html, String rule, String htmlRule) {
        if (!rule.contains(MARK)) {
            return rule;
        }
        List<String> parts = replaceSg(rule);
        return replaceItem(cutHtml(html, parts.get(0), parts.get(1)), htmlRule);
    }

    /** 旧 {@code get_html}：取页面，必要时转码；结果为假值（没有页面、空串、{@code "0"}、转码失败）时 null。 */
    private String html(String url) {
        if (empty(url)) {
            return null;
        }
        if (++fetched > MAX_PAGES) {
            throw new RuleException("一次采集读取的页面超过 " + MAX_PAGES + " 个（分页规则可能有误）");
        }
        byte[] raw = pages.fetch(url);
        if (raw == null) {
            return null;
        }
        String html = new String(raw, StandardCharsets.ISO_8859_1);
        if (!truthy(html)) {
            return null;
        }
        String cs = cfg("sourcecharset");
        if (!cs.equals("UTF-8") && !loose(cfg("sourcetype"), "4")) {
            html = iconv(cs, raw);
        }
        return truthy(html) ? html : null;
    }

    /** {@code iconv($from, 'UTF-8//TRANSLIT//IGNORE', $s)}：无法转换的字节丢弃；不认识的编码 null（旧版 false）。 */
    static String iconv(String from, byte[] raw) {
        Charset cs;
        try {
            cs = from.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(from.trim());
        } catch (IllegalCharsetNameException | java.nio.charset.UnsupportedCharsetException e) {
            return null;
        }
        CharsetDecoder d = cs.newDecoder().onMalformedInput(CodingErrorAction.IGNORE).onUnmappableCharacter(CodingErrorAction.IGNORE);
        try {
            CharBuffer chars = d.decode(ByteBuffer.wrap(raw));
            return new String(chars.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 旧辅助函数

    /** 旧 {@code cut_html}：空页面为 null（旧版 false）；开始或结束标记 {@code trim} 后为空时为空串（PHP 7.3，D-B20-4）。 */
    static String cutHtml(String html, String start, String end) {
        if (empty(html)) {
            return null;
        }
        String h = html.replace("\r", "").replace("\n", "");
        String s = trim(start.replace("\r", "").replace("\n", ""));
        String e = trim(end.replace("\r", "").replace("\n", ""));
        if (s.isEmpty()) {
            return "";
        }
        List<String> parts = IngestPhp.explode(s, h);
        String second = parts.size() > 1 ? parts.get(1) : "";
        if (e.isEmpty()) {
            return "";
        }
        return trim(IngestPhp.explode(e, second).get(0));
    }

    /** 旧 {@code replace_item}：返回字符串、null（正则出错）或 {@link Boolean#FALSE}（每行都为空）。 */
    Object replaceItem(String html, String config) {
        if (empty(config)) {
            return html;
        }
        List<Pattern> patterns = new ArrayList<>();
        List<String> replace = new ArrayList<>();
        for (String v : IngestPhp.explode("\n", config)) {
            if (empty(v)) {
                continue;
            }
            List<String> c = IngestPhp.explode(SPLIT, v);
            patterns.add(CjPcre.compile("/" + c.get(0).replace("/", "\\/") + "/i"));
            replace.add(c.size() > 1 ? c.get(1) : null);
        }
        if (patterns.isEmpty()) {
            return Boolean.FALSE;
        }
        return CjPcre.replaceAll(patterns, replace, html == null ? "" : html, CjPcre.Deadline.in(regexBudget));
    }

    /** 旧 {@code replace_sg}：按 {@code [内容]} 拆开，每段 {@code trim} 后去掉换行。 */
    static List<String> replaceSg(String rule) {
        List<String> out = new ArrayList<>();
        for (String v : IngestPhp.explode(MARK, rule)) {
            out.add(trim(v).replace("\r", "").replace("\n", ""));
        }
        return out;
    }

    /** 旧 {@code url_check($url, $baseurl, $config)}：相对地址按列表页（或 {@code page_base}）补全。 */
    String urlCheck(String url, String baseurl) {
        if (url.contains("://")) {
            return url;
        }
        Url info = Url.parse(baseurl);
        String scheme = info == null || info.scheme == null ? "" : info.scheme;
        String host = info == null || info.host == null ? "" : info.host;
        if (url.startsWith("/")) {
            return scheme + "://" + host + url;
        }
        String pageBase = cfg("page_base");
        if (truthy(pageBase)) {
            return pageBase + url;
        }
        String path = info == null || info.path == null ? "" : info.path;
        String dir = path.endsWith("/") ? path.substring(0, path.length() - 1) : dirname(path).replace('\\', '/');
        return scheme + "://" + host + dir + "/" + url;
    }

    /** PHP {@code dirname}（只认 {@code /}）。 */
    static String dirname(String path) {
        if (path.isEmpty()) {
            return "";
        }
        int end = path.length() - 1;
        while (end >= 0 && path.charAt(end) == '/') {
            end--;
        }
        if (end < 0) {
            return "/";
        }
        while (end >= 0 && path.charAt(end) != '/') {
            end--;
        }
        if (end < 0) {
            return ".";
        }
        while (end >= 0 && path.charAt(end) == '/') {
            end--;
        }
        return end < 0 ? "/" : path.substring(0, end + 1);
    }

    /** {@code parse_url} 用到的部分：协议、主机、路径（没有时 null）；PHP 返回 false 的地址（有 {@code //} 但主机为空）为 null。 */
    record Url(String scheme, String host, String path) {
        private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$", Pattern.DOTALL);

        static Url parse(String s) {
            Matcher m = SCHEME.matcher(s);
            String scheme = null;
            String rest = s;
            if (m.matches() && m.group(2).startsWith("//")) {
                scheme = m.group(1);
                rest = m.group(2);
            }
            String host = null;
            if (rest.startsWith("//")) {
                int e = 2;
                while (e < rest.length() && "/?#".indexOf(rest.charAt(e)) < 0) {
                    e++;
                }
                String auth = rest.substring(2, e);
                int at = auth.lastIndexOf('@');
                if (at >= 0) {
                    auth = auth.substring(at + 1);
                }
                int colon = auth.startsWith("[") ? auth.indexOf(':', Math.max(auth.indexOf(']'), 0)) : auth.indexOf(':');
                host = colon >= 0 ? auth.substring(0, colon) : auth;
                if (host.isEmpty()) {
                    return null;
                }
                rest = rest.substring(e);
            }
            int q = 0;
            while (q < rest.length() && "?#".indexOf(rest.charAt(q)) < 0) {
                q++;
            }
            String path = rest.substring(0, q);
            return new Url(scheme, host, path.isEmpty() ? null : path);
        }
    }

    // ------------------------------------------------------------------ PHP 值语义

    /** UTF-8 文字 → 字节文字。 */
    public static String utf8(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    /** 字节文字 → 原始字节。 */
    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 字节文字按 UTF-8 解释；不是合法 UTF-8 时 null。 */
    public static String decode(String s) {
        byte[] b = bytes(s);
        return PhpText.validUtf8(b) ? new String(b, StandardCharsets.UTF_8) : null;
    }

    /**
     * 旧 col_content 写进 {@code mac_cj_content.data} 的 {@code json_encode($html)}（默认选项）：结果为 null 时 {@code "null"}；值含非法 UTF-8 时
     * PHP 返回 false，写库时是空串（返回 {@code ""}）。
     */
    public static String json(Map<Object, Object> data) {
        if (data == null) {
            return "null";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : data.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                String d = decode(s);
                if (d == null) {
                    return "";
                }
                v = d;
            }
            out.put(String.valueOf(e.getKey()), v);
        }
        return PhpJson.encode(out);
    }

    private static String stripTags(String s) {
        return new String(PhpText.stripTags(bytes(s)), StandardCharsets.ISO_8859_1);
    }

    /** PHP {@code trim}（默认字符 空格、\t、\n、\r、\0、\x0B）。 */
    static String trim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && trimmable(s.charAt(a))) {
            a++;
        }
        while (b > a && trimmable(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean trimmable(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0b;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }

    private static boolean truthy(String s) {
        return !empty(s);
    }

    /** 字符串之间的 PHP 宽松比较（两边都是数字串时按数值）。 */
    private static boolean loose(String a, String b) {
        return IngestPhp.looseEquals(a, b);
    }

    /** {@code null}、字符串之间的 PHP 宽松比较。 */
    private static boolean looseEq(Object a, Object b) {
        if (a == null || b == null) {
            Object other = a == null ? b : a;
            return other == null || other instanceof String s && s.isEmpty();
        }
        return a instanceof String x && b instanceof String y && IngestPhp.looseEquals(x, y);
    }

    private static boolean inArray(Object v, List<?> list) {
        for (Object x : list) {
            if (looseEq(v, x)) {
                return true;
            }
        }
        return false;
    }

    /** {@code (string)$v}：null 与 false 为空串。 */
    private static String phpString(Object v) {
        return v instanceof String s ? s : v == null || Boolean.FALSE.equals(v) ? "" : String.valueOf(v);
    }

    /** 库里的整数列（文字）；不是整数时 0。 */
    private static long number(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

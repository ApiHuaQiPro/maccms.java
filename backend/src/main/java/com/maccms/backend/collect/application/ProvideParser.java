package com.maccms.backend.collect.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旧资源站解析（{@code application/common/model/Collect.php}）的本地文件版本（B18，26 文档第 3 节）：内容来自上传或本地目录的文件，
 * 不发起任何请求（E01）。逐个对应旧方法——
 * <ul>
 * <li>{@code vod}（{@code :85-102}）：{@code type == '1'} 按 XML，{@code == '2'} 按 JSON，否则先 JSON、失败再 XML；</li>
 * <li>{@code vod_xml}（{@code :140-291}）、{@code vod_json}（{@code :293-385}）、{@code art_json}（{@code :1051-1124}）、
 * {@code actor_json}（{@code :1379-1451}）、{@code role_json}（{@code :1671-1723}）、{@code website_json}（{@code :1970-2042}）、
 * {@code comment_json}（{@code :2260-2312}）。</li>
 * </ul>
 * 解析分两步：{@link #parse} 得到与绑定无关的 {@link ProvideDocument}（可缓存），{@link #bind} 按当时的 {@code bind.php} 换算
 * {@code type_id} 得到与旧方法返回值相同的数组（{@code code/msg/page/type/data}）。黄金：{@code ProvideParserGoldenTest} 对照 PHP 8.0、7.3
 * 直接调用旧方法的结果（{@code src/test/php/collect_provide_golden.php}）。
 *
 * <p>与旧版不同（26 文档第 9 节）：含 DOCTYPE 的 XML 一律拒绝（D-B18-4）；JSON 顶层是非空标量、数据行或分类行是字符串、数据行是数字或
 * {@code true}（旧版 PHP 8 上报错或得到无意义的结果）时按解析失败处理（D-B18-5）。
 */
public final class ProvideParser {

    /** 旧 {@code mac_filter_tags}（{@code application/common.php:2898-2914}）替换成 {@code *} 的片段，ASCII 不区分大小写。 */
    private static final String[] TAGS = {"{:", "<script", "<iframe", "<frameset", "<object", "onerror"};
    private static final Pattern PIC = Pattern.compile("<pic>(.*?)</pic>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ProvideParser() {
    }

    /**
     * 解析一份文件。
     *
     * @param kind        资源种类
     * @param collectType 采集源的接口类型（{@code collect_type}；只对视频有用）
     * @param ac          旧参数 {@code ac}（{@code list} 时才取分类列表）
     * @param param       旧参数 {@code param}（{@code base64_encode(collect_param)}；XML 里含 {@code ct=1} 时写进下载地址字段）
     * @param content     文件内容
     */
    public static ProvideDocument parse(ProvideKind kind, String collectType, String ac, String param, byte[] content) {
        boolean list = "list".equals(ac);
        if (kind != ProvideKind.VOD) {
            return json(kind, list, content);
        }
        Double t = collectType == null ? null : ProvideValues.numeric(collectType);
        if (t != null && t == 1.0) {
            return xml(list, param, content);
        }
        if (t != null && t == 2.0) {
            return json(kind, list, content);
        }
        ProvideDocument d = json(kind, list, content);
        return d.ok() ? d : xml(list, param, content);
    }

    // ------------------------------------------------------------------ JSON

    private static ProvideDocument json(ProvideKind kind, boolean withTypes, byte[] content) {
        String format = kind == ProvideKind.VOD ? "json" : "ok";
        if (isEmptyContent(content)) {
            return ProvideDocument.failure(kind, format, 1001, "model/collect/get_html_err", "");
        }
        Object json;
        try {
            json = ProvideJson.decode(filterTags(content));
        } catch (ProvideJson.MalformedJsonException e) {
            return ProvideDocument.failure(kind, format, 1002, "model/collect/json_err", "JSON 解析失败（" + e.getMessage() + "）");
        }
        if (ProvideValues.empty(json)) {
            return ProvideDocument.failure(kind, format, 1002, "model/collect/json_err", "JSON 为空");
        }
        if (!(json instanceof Map<?, ?>)) {
            return ProvideDocument.failure(kind, format, 1002, "model/collect/json_err", "JSON 顶层不是对象或数组");
        }
        Map<Object, Object> page = ProvideValues.array();
        page.put("page", ProvideValues.get(json, "page"));
        page.put("pagecount", ProvideValues.get(json, "pagecount"));
        page.put("pagesize", ProvideValues.get(json, "limit"));
        page.put("recordcount", ProvideValues.get(json, "total"));

        List<ProvideDocument.Row> rows = new ArrayList<>();
        if (ProvideValues.get(json, "list") instanceof Map<?, ?> items) {
            for (Map.Entry<?, ?> e : items.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) {
                    return ProvideDocument.failure(kind, format, 1002, "model/collect/json_err", "数据行不是对象");
                }
                if (!kind.binds()) {
                    rows.add(new ProvideDocument.Row(e.getKey(), null, v));
                    continue;
                }
                if (v instanceof Long || v instanceof Double || Boolean.TRUE.equals(v)) {
                    return ProvideDocument.failure(kind, format, 1002, "model/collect/json_err", "数据行不是对象");
                }
                // $array_data[$key] = $v; 再写 ['type_id']：null 与 false 自动变成数组
                Map<Object, Object> row = v instanceof Map<?, ?> m ? copy(m) : ProvideValues.array();
                String remote = ProvideValues.str(ProvideValues.get(v, "type_id"));
                row.put("type_id", 0L);
                if (kind == ProvideKind.VOD) {
                    playFromDl(row, ProvideValues.get(v, "dl"));
                }
                rows.add(new ProvideDocument.Row(e.getKey(), remote, row));
            }
        }

        List<Map<Object, Object>> types = null;
        if (kind.binds()) {
            types = new ArrayList<>();
            if (withTypes && ProvideValues.get(json, "class") instanceof Map<?, ?> cls) {
                for (Object v : cls.values()) {
                    if (v instanceof String) {
                        return ProvideDocument.failure(kind, format, 1002, "model/collect/json_err", "分类行不是对象");
                    }
                    Map<Object, Object> ty = ProvideValues.array();
                    ty.put("type_id", ProvideValues.get(v, "type_id"));
                    ty.put("type_name", ProvideValues.get(v, "type_name"));
                    types.add(ty);
                }
            }
        }
        return new ProvideDocument(kind, format, 1, "", "", page, types, rows);
    }

    /** 旧 {@code vod_json} 的 {@code dl}（{@code Collect.php:349-365}）：非空时按键值写四个播放字段（不是数组时四个字段都是空串）。 */
    private static void playFromDl(Map<Object, Object> row, Object dl) {
        if (ProvideValues.empty(dl)) {
            return;
        }
        List<String> from = new ArrayList<>();
        List<String> url = new ArrayList<>();
        List<String> server = new ArrayList<>();
        List<String> note = new ArrayList<>();
        if (dl instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                from.add(ProvideValues.str(e.getKey() instanceof Long l ? l : e.getKey()));
                url.add(ProvideValues.str(e.getValue()));
                server.add("no");
                note.add("");
            }
        }
        row.put("vod_play_from", String.join("$$$", from));
        row.put("vod_play_url", String.join("$$$", url));
        row.put("vod_play_server", String.join("$$$", server));
        row.put("vod_play_note", String.join("$$$", note));
    }

    // ------------------------------------------------------------------ XML（只有视频）

    private static ProvideDocument xml(boolean withTypes, String param, byte[] content) {
        ProvideKind kind = ProvideKind.VOD;
        if (isEmptyContent(content)) {
            return ProvideDocument.failure(kind, "xml", 1001, "model/collect/get_html_err", "");
        }
        String html = latin1(filterTags(content));
        ProvideXml.Element xml;
        try {
            xml = load(html);
            if (xml == null) {
                // 旧版：把没有包 CDATA 的 <pic> 内容包上 CDATA 再试一次（Collect.php:177-192）
                Matcher m = PIC.matcher(html);
                List<String> found = new ArrayList<>();
                while (m.find()) {
                    found.add(m.group(1));
                }
                boolean ec = false;
                for (String tt : found) {
                    if (!tt.contains("[CDATA")) {
                        ec = true;
                        html = html.replace("<pic>" + tt + "</pic>", "<pic><![CDATA[" + tt + "]]></pic>");
                    }
                }
                if (ec) {
                    xml = load(html);
                }
                if (xml == null) {
                    return ProvideDocument.failure(kind, "xml", 1002, "model/collect/xml_err", "XML 解析失败");
                }
            }
        } catch (ProvideXml.DoctypeRejectedException e) {
            return ProvideDocument.failure(kind, "xml", 1002, "model/collect/xml_err", "不接受含 DOCTYPE 的 XML");
        }

        ProvideXml.Element list = xml.child("list");
        Map<Object, Object> page = ProvideValues.array();
        page.put("page", attr(list, "page"));
        page.put("pagecount", attr(list, "pagecount"));
        page.put("pagesize", attr(list, "pagesize"));
        page.put("recordcount", attr(list, "recordcount"));

        boolean down = latin1(CjNodeAdminService.base64Decode(param == null ? new byte[0] : param.getBytes(StandardCharsets.UTF_8)))
                .contains("ct=1");
        List<ProvideDocument.Row> rows = new ArrayList<>();
        for (ProvideXml.Element video : list == null ? List.<ProvideXml.Element>of() : list.children("video")) {
            Map<Object, Object> r = ProvideValues.array();
            r.put("type_id", 0L);
            r.put("vod_id", text(video, "id"));
            r.put("vod_name", text(video, "name"));
            r.put("vod_sub", text(video, "subname"));
            r.put("vod_remarks", text(video, "note"));
            r.put("type_name", text(video, "type"));
            r.put("vod_pic", text(video, "pic"));
            r.put("vod_lang", text(video, "lang"));
            r.put("vod_area", text(video, "area"));
            r.put("vod_year", text(video, "year"));
            r.put("vod_serial", text(video, "state"));
            r.put("vod_actor", text(video, "actor"));
            r.put("vod_director", text(video, "director"));
            r.put("vod_content", text(video, "des"));
            r.put("vod_status", 1L);
            r.put("vod_type", null); // 旧版取了不存在的 list_name
            r.put("vod_time", text(video, "last"));
            r.put("vod_total", 0L);
            r.put("vod_isend", ProvideValues.empty(r.get("vod_serial")) ? 1L : 0L);
            List<String> from = new ArrayList<>();
            List<String> url = new ArrayList<>();
            List<String> server = new ArrayList<>();
            List<String> note = new ArrayList<>();
            ProvideXml.Element dl = video.child("dl");
            List<ProvideXml.Element> dd = dl == null ? List.of() : dl.children("dd");
            if (!dd.isEmpty()) {
                for (ProvideXml.Element d : dd) {
                    String flag = d.attr("flag");
                    from.add(flag == null ? "" : flag);
                    url.add(xmlReplace(d.text()));
                    server.add("no");
                    note.add("");
                }
            } else {
                from.add(text(video, "dt"));
                url.add("");
                server.add("");
                note.add("");
            }
            String p = down ? "vod_down_" : "vod_play_";
            r.put(p + "from", String.join("$$$", from));
            r.put(p + "url", String.join("$$$", url));
            r.put(p + "server", String.join("$$$", server));
            r.put(p + "note", String.join("$$$", note));
            rows.add(new ProvideDocument.Row((long) rows.size(), text(video, "tid"), r));
        }

        List<Map<Object, Object>> types = new ArrayList<>();
        ProvideXml.Element cls = xml.child("class");
        if (withTypes && cls != null) {
            for (ProvideXml.Element ty : cls.children("ty")) {
                Map<Object, Object> t = ProvideValues.array();
                String id = ty.attr("id");
                t.put("type_id", id == null ? "" : id);
                t.put("type_name", ty.text());
                types.add(t);
            }
        }
        return new ProvideDocument(kind, "xml", 1, "", "", page, types, rows);
    }

    /** {@code @simplexml_load_string($html)} 后的 {@code empty($xml)} 判断：失败或"空"时为 null。 */
    private static ProvideXml.Element load(String latin1) throws ProvideXml.DoctypeRejectedException {
        try {
            ProvideXml.Element e = ProvideXml.parse(latin1.getBytes(StandardCharsets.ISO_8859_1));
            return e.isEmptyObject() ? null : e;
        } catch (ProvideXml.MalformedXmlException e) {
            return null;
        }
    }

    private static String text(ProvideXml.Element parent, String name) {
        ProvideXml.Element e = parent.child(name);
        return e == null ? "" : e.text();
    }

    private static String attr(ProvideXml.Element e, String name) {
        String v = e == null ? null : e.attr(name);
        return v == null ? "" : v;
    }

    /** 旧 {@code vod_xml_replace}（{@code Collect.php:125-138}）。 */
    static String xmlReplace(String url) {
        String[] ji = url.replace("||", "//").split("#", -1);
        List<String> out = new ArrayList<>();
        for (String value : ji) {
            String[] parts = value.split("\\$", -1);
            if (parts.length > 1) {
                out.add(parts[0] + "$" + phpTrim(parts[1]));
            } else {
                out.add(phpTrim(parts[0]));
            }
        }
        return String.join("#", out);
    }

    /** PHP {@code trim()}：去掉首尾的空格、{@code \t \n \r \0 \x0B}。 */
    static String phpTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isTrim(s.charAt(a))) {
            a++;
        }
        while (b > a && isTrim(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean isTrim(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

    // ------------------------------------------------------------------ 绑定换算

    /**
     * 按分类绑定换算出旧方法的返回值：失败时 {@code ['code'=>…, 'msg'=>…]}；成功时 {@code code/msg/page/type/data}（角色、评论没有
     * {@code type}）。数据行的 {@code type_id}：{@code $bind_list[cjflag . '_' . 远程编号] > 0} 时为绑定的值，否则 0
     * （{@code Collect.php:210-217、342-349} 等）。
     *
     * @param url 写进 {@code page.url} 的值（旧版是请求的地址；新版是文件名）
     * @param msg 语言包取值（{@code lang()}）
     */
    public static Map<Object, Object> bind(ProvideDocument d, String cjflag, PhpArray bindList, String url,
                                           java.util.function.UnaryOperator<String> msg) {
        Map<Object, Object> res = ProvideValues.array();
        res.put("code", (long) d.code());
        if (!d.ok()) {
            res.put("msg", msg.apply(d.errorKey()) + (d.detail().isEmpty() ? "" : "：" + d.detail()));
            return res;
        }
        res.put("msg", d.format());
        Map<Object, Object> page = new LinkedHashMap<>(d.page());
        page.put("url", url);
        res.put("page", page);
        if (d.types() != null) {
            Map<Object, Object> types = ProvideValues.array();
            long i = 0;
            for (Map<Object, Object> t : d.types()) {
                types.put(i++, new LinkedHashMap<>(t));
            }
            res.put("type", types);
        }
        Map<Object, Object> data = ProvideValues.array();
        for (ProvideDocument.Row r : d.rows()) {
            Object row = r.data();
            if (r.remote() != null && row instanceof Map<?, ?> m) {
                Map<Object, Object> copy = copy(m);
                copy.put("type_id", boundTypeId(bindList, cjflag + "_" + r.remote()));
                row = copy;
            }
            data.put(r.key(), row);
        }
        res.put("data", data);
        return res;
    }

    /** {@code $bind_list[$key] > 0 ? $bind_list[$key] : 0}。 */
    static Object boundTypeId(PhpArray bindList, String key) {
        if (bindList == null) {
            return 0L;
        }
        Object k = PhpArray.normalizeKey(key);
        PhpValue v = k instanceof Long l ? bindList.get(l) : bindList.get((String) k);
        Object raw = ProvideValues.of(v);
        return ProvideValues.greaterThanZero(raw) ? raw : 0L;
    }

    // ------------------------------------------------------------------ 入库预检

    /**
     * 旧 {@code {type}_data} 循环开头的跳过条件（入库时这一行被跳过的原因，预览时提前显示）：返回语言包键，能入库时为空串。
     * 条件与顺序（{@code Collect.php:452-460、1150-1158、1474-1482、1746-1751、2066-2074、2335-2340}）：
     * <ul>
     * <li>视频、文章、网址：{@code type_id == 0} → {@code type_err}；名称 {@code empty()} → {@code name_err}；名称含过滤词 →
     * {@code name_in_filter_err}；</li>
     * <li>演员：同上，第二条是名称或性别为空 → {@code odel/collect/actor_data_require}（旧语言包键就少了开头的 m，照旧）；</li>
     * <li>角色：角色名、演员、影片名任一为空 → {@code role_data_require}；角色名含过滤词；</li>
     * <li>评论：昵称、内容、关联名任一为空 → {@code comment_data_require}；内容含过滤词。</li>
     * </ul>
     * 过滤词是 {@code maccms.php} 的 {@code collect.<种类>.filter} 按逗号拆开、去掉空串与 {@code "0"}（旧 {@code mac_array_filter}）。
     */
    public static String precheck(ProvideKind kind, Object row, String filter) {
        List<String> words = filterWords(filter);
        Object type = ProvideValues.get(row, "type_id");
        switch (kind) {
            case VOD, ART, WEBSITE -> {
                if (ProvideValues.looseEqualsZero(type)) {
                    return "model/collect/type_err";
                }
                if (ProvideValues.empty(ProvideValues.get(row, kind.nameField()))) {
                    return "model/collect/name_err";
                }
            }
            case ACTOR -> {
                if (ProvideValues.looseEqualsZero(type)) {
                    return "model/collect/type_err";
                }
                if (ProvideValues.empty(ProvideValues.get(row, "actor_name")) || ProvideValues.empty(ProvideValues.get(row, "actor_sex"))) {
                    return "odel/collect/actor_data_require";
                }
            }
            case ROLE -> {
                if (ProvideValues.empty(ProvideValues.get(row, "role_name")) || ProvideValues.empty(ProvideValues.get(row, "role_actor"))
                        || ProvideValues.empty(ProvideValues.get(row, "vod_name"))) {
                    return "model/collect/role_data_require";
                }
            }
            case COMMENT -> {
                if (ProvideValues.empty(ProvideValues.get(row, "comment_name"))
                        || ProvideValues.empty(ProvideValues.get(row, "comment_content"))
                        || ProvideValues.empty(ProvideValues.get(row, "rel_name"))) {
                    return "model/collect/comment_data_require";
                }
            }
        }
        return arrayFilter(words, ProvideValues.get(row, kind.nameField())) ? "model/collect/name_in_filter_err" : "";
    }

    static List<String> filterWords(String filter) {
        List<String> out = new ArrayList<>();
        if (filter == null) {
            return out;
        }
        for (String w : filter.split(",", -1)) {
            if (!w.isEmpty() && !w.equals("0")) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * 旧 {@code mac_array_filter($arr, $str)}（{@code application/common.php:716-730}）：{@code str_replace($arr, '*', $str) != $str}。
     * 把非空词依次换成 {@code *}，只要有一次替换就与原文不同（词本身就是 {@code *} 时替换不改变内容）。值是数组时逐个元素判断。
     */
    static boolean arrayFilter(List<String> words, Object value) {
        if (words.isEmpty()) {
            return false;
        }
        if (value instanceof Map<?, ?> m) {
            for (Object x : m.values()) {
                if (!(x instanceof Map<?, ?>) && arrayFilter(words, x)) {
                    return true;
                }
            }
            return false;
        }
        String s = ProvideValues.str(value);
        for (String w : words) {
            if (!w.equals("*") && s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 公共

    /** 旧 {@code if(empty($html))}：空串或 {@code "0"}。 */
    private static boolean isEmptyContent(byte[] c) {
        return c.length == 0 || c.length == 1 && c[0] == '0';
    }

    /** 旧 {@code mac_filter_tags($html)}：按字节、ASCII 不区分大小写地依次把各片段换成 {@code *}。 */
    static byte[] filterTags(byte[] in) {
        String s = latin1(in);
        for (String t : TAGS) {
            s = replaceAsciiIgnoreCase(s, t);
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String replaceAsciiIgnoreCase(String s, String needle) {
        StringBuilder sb = null;
        int from = 0;
        int n = needle.length();
        for (int i = 0; i + n <= s.length(); ) {
            if (matchesAt(s, i, needle)) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                }
                sb.append(s, from, i).append('*');
                i += n;
                from = i;
            } else {
                i++;
            }
        }
        if (sb == null) {
            return s;
        }
        return sb.append(s, from, s.length()).toString();
    }

    private static boolean matchesAt(String s, int at, String needle) {
        for (int j = 0; j < needle.length(); j++) {
            if (lower(s.charAt(at + j)) != needle.charAt(j)) {
                return false;
            }
        }
        return true;
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    private static Map<Object, Object> copy(Map<?, ?> m) {
        Map<Object, Object> out = ProvideValues.array();
        out.putAll(m);
        return out;
    }
}

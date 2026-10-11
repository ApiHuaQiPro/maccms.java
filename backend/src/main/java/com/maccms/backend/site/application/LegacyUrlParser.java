package com.maccms.backend.site.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.VideoListQuery;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
 */
public final class LegacyUrlParser {

    private static final String ENTRY = "/index.php";
    private static final int MAX_INPUT = 1024;
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,20}");
    private static final Pattern ROUTE_TYPE = Pattern.compile("vodtype/([0-9]{1,20})(?:-([0-9]{1,20}))?");
    private static final Pattern ROUTE_DETAIL = Pattern.compile("voddetail/([0-9]{1,20})");

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
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
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
}

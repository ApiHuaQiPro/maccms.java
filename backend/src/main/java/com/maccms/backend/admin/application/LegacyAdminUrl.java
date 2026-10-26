package com.maccms.backend.admin.application;

import com.maccms.backend.common.web.PhpInput;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台页面里的站内地址，按旧 ThinkPHP 5.0 {@code url()}（{@code Url::build}，thinkphp/library/think/Url.php）在后台入口下的结果生成：
 * {@code 入口/模块/控制器/动作} + 参数逐个写成 {@code /键/urlencode(值)}（值 trim 后为空的跳过） + {@code .html}。
 * <ul>
 * <li>{@code ''} → 当前的 模块/控制器/动作；{@code a} → 当前控制器的动作 a；{@code c/a} → 当前模块（admin）；{@code m/c/a} → 指定模块。</li>
 * <li>动作转小写，控制器按 {@code Loader::parseName} 转成小写下划线（{@code VodData} → {@code vod_data}）。</li>
 * <li>地址里的 {@code ?查询串} 按 {@code parse_str} 拆开，排在调用方给的参数前面（{@code array_merge}，同名以调用方为准）。</li>
 * </ul>
 * 只覆盖后台用到的写法（菜单、快捷菜单、分页地址、跳转地址），不处理路由别名、命名路由与域名部署（后台没有）。黄金样本里出现过的
 * 地址都由 AdminGoldenMvcTest 逐个比对。
 */
public final class LegacyAdminUrl {

    private final String root;
    private final String controller;
    private final String action;

    /**
     * @param root       入口的站内路径（部署前缀 + 入口文件，如 {@code /_admin.php}）
     * @param controller 当前控制器（旧 {@code request()->controller()}，如 {@code Index}；只用于 {@code ''} 与单段地址）
     * @param action     当前动作（小写）
     */
    public LegacyAdminUrl(String root, String controller, String action) {
        this.root = root;
        this.controller = controller;
        this.action = action;
    }

    /** {@code url($url)}。 */
    public String build(String url) {
        return build(url, new LinkedHashMap<>());
    }

    /** {@code url($url, $vars)}。 */
    public String build(String url, Map<String, String> vars) {
        String u = url == null ? "" : url;
        String anchor = "";
        int hash = u.indexOf('#');
        if (hash >= 0) {
            anchor = u.substring(hash + 1);
            u = u.substring(0, hash);
        }
        String query = null;
        int q = u.indexOf('?');
        if (q >= 0) {
            query = u.substring(q + 1);
            u = u.substring(0, q);
        }
        String path;
        if (u.startsWith("/")) {
            path = u.substring(1);
        } else {
            String c = controller;
            String a;
            String m = "admin/";
            if (u.isEmpty()) {
                a = action;
            } else {
                String[] parts = u.split("/", -1);
                int n = parts.length;
                a = parts[n - 1];
                if (n >= 2) {
                    c = parts[n - 2];
                }
                if (n >= 3) {
                    m = parts[n - 3] + "/";
                }
            }
            path = m + parseName(c) + "/" + AdminAuthorization.asciiLower(a);
        }
        LinkedHashMap<String, String> all = new LinkedHashMap<>();
        if (query != null) {
            for (Map.Entry<String, PhpInput.Value> e : PhpInput.parseQuery(query).entrySet()) {
                if (!e.getValue().array()) {
                    all.put(e.getKey(), e.getValue().text());
                }
            }
        }
        all.putAll(vars);
        StringBuilder sb = new StringBuilder(path);
        String suffix = path.isEmpty() ? "" : ".html";
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (!e.getValue().trim().isEmpty()) {
                sb.append('/').append(e.getKey()).append('/').append(urlencode(e.getValue()));
            }
        }
        sb.append(suffix);
        if (!anchor.isEmpty()) {
            sb.append('#').append(anchor);
        }
        String r = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        return r + "/" + (sb.charAt(0) == '/' ? sb.substring(1) : sb.toString());
    }

    /** ThinkPHP {@code Loader::parseName($name)}：大写字母前加下划线、转小写、去掉首尾下划线。 */
    static String parseName(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                sb.append('_').append((char) (ch + 32));
            } else {
                sb.append(ch);
            }
        }
        int a = 0;
        int b = sb.length();
        while (a < b && sb.charAt(a) == '_') {
            a++;
        }
        while (b > a && sb.charAt(b - 1) == '_') {
            b--;
        }
        return sb.substring(a, b);
    }

    /** PHP {@code http_build_query($vars)}（标量参数）：键、值各按 {@code urlencode}，以 {@code &} 连接。 */
    public static String httpBuildQuery(Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(urlencode(e.getKey())).append('=').append(urlencode(e.getValue()));
        }
        return sb.toString();
    }

    /** PHP {@code urlencode}：空格为 {@code +}，{@code -_.} 与字母数字之外的字节都是 {@code %XX}（大写）。 */
    static String urlencode(String s) {
        // Java 的 URLEncoder 与 PHP urlencode 只差 "*"（Java 不编码）与 "~"（两者都编码为 %7E）
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("*", "%2A");
    }
}

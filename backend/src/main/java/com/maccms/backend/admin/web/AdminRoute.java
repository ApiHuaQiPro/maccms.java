package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminAuthorization;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台入口之后的路径（旧 PATH_INFO）按 ThinkPHP 5.0 的规则解析（21 文档第 4.1 节，黄金 {@code anonymous} 的地址写法逐个比对）：
 * <ol>
 * <li>去掉开头的 {@code /}，末尾的 {@code .html|.htm|.shtm|.shtml|.xml}（不分大小写，{@code url_html_suffix}）；</li>
 * <li>按 {@code /} 拆成 模块/控制器/动作/参数…；空段取默认值（控制器 {@code Index}、动作 {@code index}），控制器与动作转小写
 * （{@code url_convert}）；</li>
 * <li>其余各段按 {@code (\w+)\|([^\|]+)} 成对取作参数（键原样，值 {@code strip_tags}）。</li>
 * </ol>
 * 模块必须<b>原样</b>等于 {@code admin}（旧 common/behavior/Begin.php：后台入口下模块为空或不是 admin 时 302 到 {@code admin/index/index}）。
 */
record AdminRoute(boolean redirectHome, String controller, String action, String controllerName, Map<String, String> vars) {

    private static final Pattern SUFFIX = Pattern.compile("\\.(html|htm|shtm|shtml|xml)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAIR = Pattern.compile("([A-Za-z0-9_]+)\\|([^|]+)");
    private static final Pattern TAG = Pattern.compile("<[^>]*>?");

    /** @param pathInfo 入口之后、已解码一次的路径（可为空串） */
    static AdminRoute parse(String pathInfo) {
        String p = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        p = SUFFIX.matcher(p).replaceFirst("");
        if (p.isEmpty()) {
            return new AdminRoute(true, null, null, null, Map.of());
        }
        List<String> parts = Arrays.asList(p.split("/", -1));
        if (!parts.get(0).equals("admin")) {
            return new AdminRoute(true, null, null, null, Map.of());
        }
        String c = parts.size() > 1 && !parts.get(1).isEmpty() ? stripTags(parts.get(1)) : "Index";
        String a = parts.size() > 2 && !parts.get(2).isEmpty() ? stripTags(parts.get(2)) : "index";
        Map<String, String> vars = new LinkedHashMap<>();
        if (parts.size() > 3) {
            Matcher m = PAIR.matcher(String.join("|", parts.subList(3, parts.size())));
            while (m.find()) {
                vars.put(m.group(1), stripTags(m.group(2)));
            }
        }
        String controller = AdminAuthorization.asciiLower(c);
        return new AdminRoute(false, controller, AdminAuthorization.asciiLower(a), ucfirst(controller),
                Collections.unmodifiableMap(vars));
    }

    /** 当前 {@code 控制器/动作}。 */
    String code() {
        return controller + "/" + action;
    }

    /**
     * PHP {@code strip_tags} 的简化：去掉 {@code <…>} 与末尾未闭合的 {@code <…}。后台地址里的参数只有数字与英文（21 文档第 4.1 节）。
     */
    static String stripTags(String s) {
        return s.indexOf('<') < 0 ? s : TAG.matcher(s).replaceAll("");
    }

    /** 旧 {@code request()->controller()}：{@code Loader::parseName($c, 1)}（下划线后转大写、首字母大写）。 */
    private static String ucfirst(String c) {
        StringBuilder sb = new StringBuilder(c.length());
        boolean up = true;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '_' && i + 1 < c.length() && Character.isLetter(c.charAt(i + 1))) {
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }
}

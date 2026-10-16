package com.maccms.backend.site.application;

import java.util.HashSet;
import java.util.Set;

/**
 * 站内地址查询串的白名单检查（09 方案第 7.2.2 节），只用于 SSR 搜索页链接（{@link LegacyUrlBuilder#vodSearchLink}）。
 *
 * <p>结构为 {@code 键=值(&键=值)*}：键只能是 {@link LegacyUrlBuilder#SEARCH_LINK_KEYS} 之一且不重复，值非空，只含
 * {@code [A-Za-z0-9._+-]} 与大写十六进制的 {@code %HH}（旧 {@code urlencode} 的输出形态），{@code %HH} 不得是控制字符
 * {@code %00-%1F}、{@code %7F}。{@code %2F}、{@code %5C}、{@code %2E} 在这里允许：点段消除只作用于路径，Servlet 容器与
 * nginx 的 location 匹配都不解码查询串，它们不会改变路径结构。路径部分仍由 {@link SitePaths} 检查，规则不放宽。
 */
final class SiteQueries {

    private SiteQueries() {
    }

    /** 合法时原样返回，否则抛 {@link IllegalArgumentException}（信息不含取值）。 */
    static String check(String query) {
        if (!isValid(query)) {
            throw new IllegalArgumentException("站内查询串不合法");
        }
        return query;
    }

    static boolean isValid(String query) {
        if (query == null || query.isEmpty() || query.length() > SitePaths.MAX_LENGTH) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String item : query.split("&", -1)) {
            int eq = item.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            String key = item.substring(0, eq);
            String value = item.substring(eq + 1);
            if (!LegacyUrlBuilder.SEARCH_LINK_KEYS.contains(key) || !seen.add(key) || !validValue(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validValue(String v) {
        if (v.isEmpty()) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '%') {
                if (i + 2 >= v.length() || !upperHex(v.charAt(i + 1)) || !upperHex(v.charAt(i + 2))) {
                    return false;
                }
                int b = Integer.parseInt(v.substring(i + 1, i + 3), 16);
                if (b < 0x20 || b == 0x7f) {
                    return false;
                }
                i += 2;
            } else if (!plain(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean plain(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '+' || c == '-';
    }

    private static boolean upperHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }
}

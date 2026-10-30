package com.maccms.backend.site.application;

/**
 * 站内路径（相对站点根，不以 / 开头）的规范形态检查，canonical、页面内链接与旧 URL 生成器共用。
 *
 * <p>允许的字符：{@code [A-Za-z0-9._~/+-]}，以及大写十六进制的百分号编码 {@code %HH}（旧 {@code urlencode} 的输出形态）。
 * 编码后的斜线 {@code %2F}、反斜线 {@code %5C}、点 {@code %2E} 与控制字符 {@code %00-%1F}、{@code %7F} 拒绝：
 * 它们在 Web 服务器解码后会改变路径结构或带入控制字符。不允许空段、{@code .}、{@code ..} 段（末尾的 / 允许）。
 *
 * <p>长度上限 {@link #MAX_LENGTH} 是站内地址的预算：与 {@link SiteSettings#MAX_BASE_URL_LENGTH} 相加等于
 * {@link SafeUrl#MAX_LENGTH}，所以配置通过校验后，任何合法站内路径拼出的 canonical / 页面内链接都不会超出 SafeUrl 上限。
 */
final class SitePaths {

    /**
     * 站内地址（相对站点根）的最大长度。现有生成结果中最长的是旧搜索页 {@code vodSearch} 在公开接口参数上限下的组合
     * （1235，SiteSettingsTest 锁定），解析器只接受 1024 以内的请求路径。
     */
    static final int MAX_LENGTH = 1280;

    private SitePaths() {
    }

    /** 合法时原样返回，否则抛 {@link IllegalArgumentException}（信息不含取值）。 */
    static String check(String sitePath) {
        if (!isValid(sitePath)) {
            throw new IllegalArgumentException("站内路径不合法");
        }
        return sitePath;
    }

    static boolean isValid(String sitePath) {
        if (sitePath == null || sitePath.startsWith("/") || sitePath.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < sitePath.length(); i++) {
            char c = sitePath.charAt(i);
            if (c == '%') {
                if (i + 2 >= sitePath.length() || !upperHex(sitePath.charAt(i + 1)) || !upperHex(sitePath.charAt(i + 2))) {
                    return false;
                }
                int b = Integer.parseInt(sitePath.substring(i + 1, i + 3), 16);
                if (b < 0x20 || b == 0x7f || b == '/' || b == '\\' || b == '.') {
                    return false;
                }
                i += 2;
            } else if (!plain(c)) {
                return false;
            }
        }
        String[] segments = sitePath.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            boolean lastEmpty = i == segments.length - 1 && s.isEmpty();
            if (!lastEmpty && (s.isEmpty() || s.equals(".") || s.equals(".."))) {
                return false;
            }
        }
        return true;
    }

    private static boolean plain(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '~' || c == '/' || c == '+' || c == '-';
    }

    private static boolean upperHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }
}

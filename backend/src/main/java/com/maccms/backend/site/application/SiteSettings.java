package com.maccms.backend.site.application;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 校验后的站点设置（site 启用时才创建）。规则见 docs/architecture/04-site-template-spike.md 第 6.2 节与第 9 节。
 * 不合法时抛 {@link IllegalStateException}，信息只列出配置项名称与规则，不回显取值。
 */
public record SiteSettings(String name, SafeUrl baseUrl, SafeUrl homePath, String keywords, String description,
                           String icp, Optional<SafeUrl> logo, boolean closed, String closedMessage) {

    public static final String DEFAULT_CLOSED_MESSAGE = "站点维护中，请稍后访问。";

    public static SiteSettings from(SiteProperties p) {
        List<String> errors = new ArrayList<>();

        String name = trim(p.name());
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 64 || hasControl(name)) {
            errors.add("maccms.site.name（必填，1～64 个字符，不含控制字符）");
        }
        SafeUrl baseUrl = parseBaseUrl(trim(p.baseUrl()));
        if (baseUrl == null) {
            errors.add("maccms.site.base-url（必填，http/https 绝对地址，有主机名，不含用户信息、查询串、片段，"
                    + "路径以 / 结尾）");
        }
        String keywords = text(p.keywords(), "maccms.site.keywords", 200, errors);
        String description = text(p.description(), "maccms.site.description", 500, errors);
        String icp = text(p.icp(), "maccms.site.icp", 64, errors);

        String logoRaw = trim(p.logoUrl());
        Optional<SafeUrl> logo = logoRaw.isEmpty() ? Optional.empty() : SafeUrl.parse(logoRaw);
        if (!logoRaw.isEmpty() && logo.isEmpty()) {
            errors.add("maccms.site.logo-url（留空，或 http/https 绝对地址，或以单个 / 开头的站内路径）");
        }

        String status = trim(p.status());
        boolean closed = false;
        if (status.equals("closed")) {
            closed = true;
        } else if (!status.isEmpty() && !status.equals("open")) {
            errors.add("maccms.site.status（open 或 closed）");
        }

        String closedMessage = trim(p.closedMessage()).isEmpty() ? DEFAULT_CLOSED_MESSAGE : trim(p.closedMessage());
        if (closedMessage.codePointCount(0, closedMessage.length()) > 200 || hasControl(closedMessage)) {
            errors.add("maccms.site.closed-message（1～200 个字符，不含控制字符）");
        }

        if (!isFalseOrUnset(p.canonicalRedirect())) {
            errors.add("maccms.site.canonical-redirect（canonical 301 尚未批准，只能为 false）");
        }
        if (!isFalseOrUnset(p.hitCounterEnabled())) {
            errors.add("maccms.site.hit-counter-enabled（点击计数尚未决定，只能为 false）");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("site 已启用但站点配置缺失或不合法：" + String.join("；", errors));
        }
        SafeUrl home = new SafeUrl(URI.create(baseUrl.href()).getRawPath());
        return new SiteSettings(name, baseUrl, home, keywords, description, icp, logo, closed, closedMessage);
    }

    /**
     * 由配置的 base-url 生成 canonical 绝对地址，不使用 Host、X-Forwarded-Host 等请求头。
     *
     * @param sitePath 相对站点根的路径，规则见 {@link SitePaths}（旧 URL 由 {@link LegacyUrlBuilder} 生成）
     */
    public SafeUrl canonical(String sitePath) {
        return new SafeUrl(baseUrl.href() + SitePaths.check(sitePath));
    }

    /**
     * 页面内链接（样式表、站内链接）用的站内绝对路径：base-url 的路径部分 + sitePath。
     * 浏览器看到的是对外地址空间；base-url 带子目录时由反向代理把该前缀映射到本应用根路径（部署时评审）。
     */
    public SafeUrl publicPath(String sitePath) {
        return new SafeUrl(homePath.href() + SitePaths.check(sitePath));
    }

    private static SafeUrl parseBaseUrl(String raw) {
        Optional<SafeUrl> url = SafeUrl.parse(raw);
        if (url.isEmpty() || !url.get().isAbsolute() || raw.length() > 200) {
            return null;
        }
        URI uri = URI.create(raw);
        String path = uri.getRawPath();
        boolean ok = uri.getRawQuery() == null && uri.getRawFragment() == null && !raw.contains("?")
                && !raw.contains("#") && path != null && path.endsWith("/") && !path.contains("//")
                && !path.contains("/./") && !path.contains("/../") && !path.contains("%")
                && uri.getScheme().equals(uri.getScheme().toLowerCase(Locale.ROOT));
        return ok ? url.get() : null;
    }

    private static String text(String raw, String key, int max, List<String> errors) {
        String v = trim(raw);
        if (v.codePointCount(0, v.length()) > max || hasControl(v)) {
            errors.add(key + "（最多 " + max + " 个字符，不含控制字符）");
        }
        return v;
    }

    private static boolean isFalseOrUnset(String raw) {
        String v = trim(raw);
        return v.isEmpty() || v.equalsIgnoreCase("false");
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    private static boolean hasControl(String s) {
        return s.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029);
    }
}

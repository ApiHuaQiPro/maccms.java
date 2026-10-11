package com.maccms.backend.site.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * 通过白名单校验、可以放进 HTML 属性（href/src）的 URL。模板里的 URL 属性只能输出 {@code SafeUrl.href()}
 * （SiteTemplateGuardTest 检查），jte 的默认转义只防止跳出属性，不能代替协议校验（04 报告第 3.2 节）。
 *
 * <p>只接受两种形态：
 * <ul>
 * <li>{@code http}/{@code https} 绝对地址：有主机名，没有用户信息；</li>
 * <li>站内绝对路径：以单个 {@code /} 开头，不能以 {@code //} 或 {@code /\} 开头（否则浏览器会当成其他主机）。</li>
 * </ul>
 * 任何位置出现空白、控制字符、反斜线、引号、尖括号、反引号都拒绝，所以 {@code java<TAB>script:}、
 * {@code javascript:}、{@code data:}、协议相对地址 {@code //host} 都不会通过。
 *
 * @param href 原样保存的地址（不做规范化，输出时由 jte 按属性上下文转义）
 */
public record SafeUrl(String href) {

    static final int MAX_LENGTH = 2048;

    public SafeUrl {
        if (!isAllowed(href)) {
            // 不把被拒绝的值写进异常：它可能来自配置或数据
            throw new IllegalArgumentException("URL 不在白名单内");
        }
    }

    /** 合法时返回 SafeUrl，否则返回空。 */
    public static Optional<SafeUrl> parse(String raw) {
        return isAllowed(raw) ? Optional.of(new SafeUrl(raw)) : Optional.empty();
    }

    /** 是否为 http/https 绝对地址（站内路径返回 false）。 */
    public boolean isAbsolute() {
        return !href.startsWith("/");
    }

    static boolean isAllowed(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x20 || c == 0x7f || Character.isISOControl(c) || Character.isWhitespace(c)
                    || Character.isSpaceChar(c) || c == '\\' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`'
                    || c == ' ' || c == ' ' || c == '﻿') {
                return false;
            }
        }
        if (raw.charAt(0) == '/') {
            return raw.length() == 1 || raw.charAt(1) != '/';
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return (scheme.equals("http") || scheme.equals("https"))
                && !uri.isOpaque()
                && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getRawUserInfo() == null
                && !uri.getRawAuthority().contains("@");
    }
}

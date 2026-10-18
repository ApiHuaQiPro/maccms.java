package com.maccms.backend.site.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交给浏览器（播放器脚本、下载链接、播放器配置）的地址的分类（B05，W1/W2，docs/architecture/15-b05-readonly-pages.md 第 6.3 节）。
 * "本站"只按配置的 base-url 主机判定（B02 规则，不看请求的 Host 头）。
 *
 * <p>旧播放器脚本把地址拼进 HTML（{@code static/player/iframe.js}、{@code parse.js}、{@code flv.js} 等以字符串拼接 iframe/embed），
 * 所以含空白、控制字符、引号、尖括号、反引号、反斜线的地址一律归为 {@link Kind#UNSAFE}，不交给浏览器；{@code javascript:}、
 * {@code data:} 等不在白名单内的协议同样如此（与旧版的差异）。
 */
public final class BrowserAddress {

    /** 分类。 */
    public enum Kind {
        /** 空串。 */
        EMPTY,
        /** 站内地址：以单个 / 开头、没有协议的相对地址，或主机是本站的绝对地址。 */
        LOCAL,
        /** http/https 绝对地址或协议相对地址，主机不是本站（W2/W1 打开时才输出）。 */
        EXTERNAL,
        /** 白名单内的其他协议（磁力、迅雷、电驴、ftp、rtmp、rtsp、mms），按外部地址对待。 */
        OTHER_SCHEME,
        /** 不能交给浏览器。 */
        UNSAFE
    }

    /**
     * @param cspSource 外部 http/https 地址的 CSP 来源表达式（{@code scheme://host[:port]}，协议相对地址为 {@code host[:port]}）；
     *                  主机名不是 ASCII 的地址没有
     */
    public record Result(Kind kind, Optional<String> cspSource) {

        /** 外部地址（含其他协议）。 */
        public boolean external() {
            return kind == Kind.EXTERNAL || kind == Kind.OTHER_SCHEME;
        }
    }

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Set<String> OTHER_SCHEMES = Set.of("magnet", "thunder", "ed2k", "ftp", "rtmp", "rtsp", "mms");
    private static final Pattern CSP_HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    private BrowserAddress() {
    }

    /**
     * @param raw      地址原文
     * @param siteHost base-url 的主机名（小写，没有末尾的点）
     */
    public static Result classify(String raw, String siteHost) {
        if (raw == null || raw.isEmpty()) {
            return new Result(Kind.EMPTY, Optional.empty());
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x20 || c == 0x7f || Character.isISOControl(c) || Character.isWhitespace(c)
                    || Character.isSpaceChar(c) || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`'
                    || c == '\\' || c == '﻿' || c == ' ' || c == ' ') {
                return unsafe();
            }
        }
        if (raw.startsWith("//")) {
            return absolute("http:" + raw, siteHost, false);
        }
        if (raw.startsWith("/")) {
            return new Result(Kind.LOCAL, Optional.empty());
        }
        Matcher m = SCHEME.matcher(raw);
        if (!m.find()) {
            return new Result(Kind.LOCAL, Optional.empty());
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            return absolute(raw, siteHost, true);
        }
        if (OTHER_SCHEMES.contains(scheme)) {
            return new Result(Kind.OTHER_SCHEME, Optional.empty());
        }
        return unsafe();
    }

    /** base-url 的主机名（小写、去掉末尾的点）。 */
    public static String hostOf(SafeUrl baseUrl) {
        String host = URI.create(baseUrl.href()).getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static Result absolute(String url, String siteHost, boolean withScheme) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return unsafe();
        }
        if (uri.isOpaque() || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                || uri.getRawAuthority() == null || uri.getRawAuthority().contains("@")) {
            return unsafe();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals(siteHost)) {
            return new Result(Kind.LOCAL, Optional.empty());
        }
        Optional<String> source = Optional.empty();
        if (CSP_HOST.matcher(host).matches()) {
            String hostPort = host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            source = Optional.of(withScheme ? uri.getScheme().toLowerCase(Locale.ROOT) + "://" + hostPort : hostPort);
        }
        return new Result(Kind.EXTERNAL, source);
    }

    private static Result unsafe() {
        return new Result(Kind.UNSAFE, Optional.empty());
    }
}

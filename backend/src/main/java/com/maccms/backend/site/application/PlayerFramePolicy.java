package com.maccms.backend.site.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 同源播放器内页（{@code vod/player}）的专用内容安全策略（B05，队长裁定 D-B05-1）。其余页面（含外层 {@code vod/play}）保持
 * {@code script-src 'none'} 的严格策略。
 *
 * <ul>
 * <li>{@code script-src 'self' 'nonce-…'}：每个响应新生成 nonce；旧 {@code static/js/player.js} 用 {@code document.write} 写出的
 * {@code static/player/<来源>.js} 是解析器插入的同源脚本，靠 {@code 'self'} 放行（{@code 'strict-dynamic'} 不放行解析器插入的脚本，
 * 所以不用）。不加 {@code 'unsafe-inline'}，内联脚本与 {@code javascript:} 地址都不执行。</li>
 * <li>{@code style-src 'self' 'unsafe-inline'}：player.js 写出内联 {@code <style>} 与 style 属性，只对样式放宽。</li>
 * <li>W1 打开时只加入本页实际用到的解析地址、{@code play.prestrain}/{@code play.buffer} 的来源（{@code frame-src}），来源为 iva 时
 * 加入 iva 播放器脚本的主机（{@code *.videojj.com}，static/player/iva.js:2，既定的功能例外）；W2 打开时只加入本集外部地址的来源
 * （{@code frame-src}、{@code media-src}、{@code connect-src}、{@code img-src}），从不加入 {@code script-src}。</li>
 * </ul>
 */
public final class PlayerFramePolicy {

    /** iva 播放器脚本的主机（static/player/iva.js:2 写出 //cytroncdn.videojj.com/latest/cytron.core.js）。 */
    public static final String IVA_HOSTS = "*.videojj.com";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SOURCE = Pattern.compile(
            "(?:(?:https?)://)?(?:\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(?::[0-9]{1,5})?");

    private final String nonce;
    private final Set<String> scripts = new LinkedHashSet<>();
    private final Set<String> frames = new LinkedHashSet<>();
    private final Set<String> media = new LinkedHashSet<>();

    private PlayerFramePolicy(String nonce) {
        this.nonce = nonce;
    }

    /** 新的策略，带随机 nonce（128 位）。 */
    public static PlayerFramePolicy create() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return new PlayerFramePolicy(Base64.getEncoder().encodeToString(b));
    }

    public String nonce() {
        return nonce;
    }

    /** W1：iva 播放器的外部脚本。 */
    public PlayerFramePolicy allowIva() {
        scripts.add(IVA_HOSTS);
        frames.add(IVA_HOSTS);
        media.add(IVA_HOSTS);
        return this;
    }

    /** W1：解析页、缓冲/预加载页（iframe）的来源。 */
    public PlayerFramePolicy allowFrame(String source) {
        frames.add(check(source));
        return this;
    }

    /** W2：本集外部播放地址的来源（iframe 型播放器、媒体、分片请求、封面）。 */
    public PlayerFramePolicy allowMedia(String source) {
        String s = check(source);
        frames.add(s);
        media.add(s);
        return this;
    }

    /** 外部来源（不含 'self'），供测试断言"无外部来源"。 */
    public List<String> externalSources() {
        Set<String> all = new LinkedHashSet<>(scripts);
        all.addAll(frames);
        all.addAll(media);
        return List.copyOf(all);
    }

    public String header() {
        return "default-src 'none'; script-src 'self' 'nonce-" + nonce + "'" + join(scripts)
                + "; style-src 'self' 'unsafe-inline'; img-src 'self' data:" + join(media)
                + "; media-src 'self' blob:" + join(media) + "; frame-src 'self'" + join(frames)
                + "; connect-src 'self'" + join(media) + "; font-src 'self'; object-src 'none'; base-uri 'none'"
                + "; form-action 'self'; frame-ancestors 'self'";
    }

    private static String join(Set<String> sources) {
        StringBuilder sb = new StringBuilder();
        for (String s : sources) {
            sb.append(' ').append(s);
        }
        return sb.toString();
    }

    private static String check(String source) {
        if (source == null || !SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("CSP 来源不合法");
        }
        return source;
    }
}

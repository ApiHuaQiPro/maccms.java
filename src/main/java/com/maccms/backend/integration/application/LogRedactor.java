package com.maccms.backend.integration.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第三方请求与响应写日志前的脱敏（B23，路线图第 4.3 节 ①；26 文档第 5 节）。
 *
 * <p>脱敏对象：配置中的密钥字段、签名、{@code access_token} 等令牌、验证码、手机号。识别方式：
 * <ul>
 * <li>按字段名：查询串与表单的 {@code name=value}、JSON 的 {@code "name": value}、XML 的 {@code <name>value</name>}，
 * 字段名命中 {@link #isSensitiveName} 时值换成 {@link #MASK}；</li>
 * <li>{@code Authorization}/{@code Cookie} 等请求头、{@code Bearer} 令牌、URL 中的 {@code user:password@}；</li>
 * <li>按形态：中国大陆手机号（可带 {@code +86}）；</li>
 * <li>调用方给出的已知密钥值（{@link ThirdPartyConfig#secretValues()}）：原文出现在任何位置都替换。</li>
 * </ul>
 * 宁可多遮：字段名规则会把 {@code sms.sign}（短信签名名称）、{@code code} 一类不一定敏感的值也遮掉。
 * 结果另截断到 {@link #MAX_LENGTH} 个字符。本类只做文本处理，不写日志。
 */
public final class LogRedactor {

    /** 替换后的占位。 */
    public static final String MASK = "***";

    /** 脱敏结果的最大长度（字符），超出部分截断并标注原长度。 */
    public static final int MAX_LENGTH = 512;

    /** 已知密钥值短于此长度时不按原文替换（太短会误伤正常文本；这类值仍由字段名规则处理）。 */
    static final int MIN_KNOWN_SECRET_LENGTH = 4;

    /** 字段名（去掉非字母数字、转小写后）以这些词结尾即视为敏感。 */
    private static final Pattern SENSITIVE_SUFFIX = Pattern.compile(
            "(key|secret|token|sign|signature|password|passwd|pwd|pass|ticket|cookie|code|captcha|verify|vcode"
                    + "|mobile|phone|phones|phonenumber|phonenumbers|tel|mchid|authorization|auth|credential|credentials)$");

    /** 字段名中含这些词即视为敏感（不论位置）。 */
    private static final Pattern SENSITIVE_PART = Pattern.compile("(secret|token|password|passwd|privatekey|apikey)");

    /** 以 code 结尾但只是状态码的字段，不遮。 */
    private static final Pattern NON_SECRET_CODE = Pattern.compile(
            "(err|error|return|result|status|resp|response|http|sub|biz|msg|ret|template|tpl)code$");

    private static final Pattern USERINFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.\\-]*://)[^/?#@\\s]+@");
    private static final Pattern HEADER = Pattern.compile(
            "(?i)\\b(authorization|proxy-authorization|cookie|set-cookie)(\\s*:\\s*)[^\\r\\n]*");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)(\\s+)[A-Za-z0-9._~+/=\\-]+");
    private static final Pattern XML_ELEMENT = Pattern.compile(
            "<([A-Za-z_][\\w.\\-]*)(\\s[^>]*)?>(<!\\[CDATA\\[.*?]]>|[^<]*)</\\1\\s*>", Pattern.DOTALL);
    private static final Pattern JSON_MEMBER = Pattern.compile(
            "(\"((?:[^\"\\\\]|\\\\.){1,64})\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d[\\d.eE+\\-]*|true|false)");
    private static final Pattern PAIR = Pattern.compile(
            "(?<![\\w\\-\\[\\]])([A-Za-z_][\\w\\-]*(?:\\[[\\w\\-]*])*)(=)([^&\\s\"'<>,;]*)");
    private static final Pattern MOBILE = Pattern.compile("(?<![\\d])(?:\\+?86[\\- ]?)?1[3-9]\\d{9}(?![\\d])");

    private LogRedactor() {
    }

    /** 按规则脱敏。 */
    public static String redact(String text) {
        return redact(text, List.of());
    }

    /** 按规则脱敏，另把 {@code knownSecrets} 中的值（如配置里的密钥）按原文替换。null 返回空串。 */
    public static String redact(String text, Collection<String> knownSecrets) {
        if (text == null) {
            return "";
        }
        String s = text;
        List<String> known = new ArrayList<>();
        for (String k : knownSecrets) {
            if (k != null && k.length() >= MIN_KNOWN_SECRET_LENGTH) {
                known.add(k);
            }
        }
        // 长的先换，避免短值是长值的一部分时只遮一半
        known.sort(Comparator.comparingInt(String::length).reversed());
        for (String k : known) {
            s = s.replace(k, MASK);
        }
        s = USERINFO.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK + "@"));
        s = HEADER.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + MASK));
        s = BEARER.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + MASK));
        s = replaceNamed(XML_ELEMENT, s, m -> isSensitiveName(m.group(1))
                ? "<" + m.group(1) + (m.group(2) == null ? "" : m.group(2)) + ">" + MASK + "</" + m.group(1) + ">" : null);
        s = replaceNamed(JSON_MEMBER, s, m -> isSensitiveName(m.group(2))
                ? m.group(1) + "\"" + MASK + "\"" : null);
        s = replaceNamed(PAIR, s, m -> isSensitiveName(lastSegment(m.group(1))) && !m.group(3).isEmpty()
                ? m.group(1) + m.group(2) + MASK : null);
        s = MOBILE.matcher(s).replaceAll(MASK);
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH) + "…(" + text.length() + " chars)";
        }
        return s;
    }

    /**
     * 字段名是否敏感：去掉非字母数字并转小写后，以密钥、签名、令牌、验证码、手机号等词结尾，或含 secret/token/password；
     * 以 code 结尾的状态码字段（{@code errcode}、{@code return_code} 等）除外。
     */
    public static boolean isSensitiveName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty() || NON_SECRET_CODE.matcher(n).find()) {
            return false;
        }
        return SENSITIVE_SUFFIX.matcher(n).find() || SENSITIVE_PART.matcher(n).find();
    }

    /** {@code user[mobile]} → {@code mobile}。 */
    private static String lastSegment(String name) {
        int open = name.lastIndexOf('[');
        if (open >= 0 && name.endsWith("]") && open + 1 < name.length() - 1) {
            return name.substring(open + 1, name.length() - 1);
        }
        return name;
    }

    /** {@code replacement} 返回 null 时保留原文。 */
    private static String replaceNamed(Pattern p, String s, Function<Matcher, String> replacement) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            String r = replacement.apply(m);
            m.appendReplacement(sb, Matcher.quoteReplacement(r == null ? m.group() : r));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

package com.maccms.backend.site.application;

import org.jsoup.nodes.Entities;

/**
 * 普通文本字段（标题、演员、分类名等）的字符引用处理：把库内值按"旧模板原样输出、由浏览器解码一次"的显示结果还原为纯文本，
 * 之后交给模板按上下文转义。规则与黄金向量见 docs/architecture/07-rich-text-filter.md 第 4 节。
 *
 * <p>只解码<b>以分号结尾</b>的完整字符引用，且只解码一次（结果不会再被解码）：
 * <ul>
 * <li>{@code &name;}：name 在 HTML5 命名引用表中（jsoup 实体表）时解码，否则原样；</li>
 * <li>{@code &#NN;} / {@code &#xHH;}：按浏览器规则解码（0x80～0x9F 按 windows-1252 重映射）；
 * 0、代理区、超出 U+10FFFF 以及解码后是控制字符（制表、换行、换页、回车除外）的，替换为 U+FFFD；</li>
 * <li>不带分号的写法（{@code &amp}、{@code &copy}、{@code &#65}）与其他 {@code &}：原样保留。浏览器会解码其中一部分，
 * 新版不解码，避免把 {@code a=1&times=2} 这类合法字面文本改成 {@code a=1×=2}。</li>
 * </ul>
 * 不删除、不解释标签：{@code <b>} 作为文字保留，由模板转义后显示为字面文字。
 *
 * <p>只用于确认"库内值按 HTML 写入"的字段（字段清单见文档）；不用于 URL、富文本（富文本由 {@link RichTextSanitizer}
 * 在解析时解码）、已经解码过的值或任何非 HTML 来源，否则会造成二次解码。
 */
public final class LegacyTextEntities {

    /** windows-1252 中 0x80～0x9F 对应的字符（HTML 规范「numeric character reference end state」的替换表），0 表示不替换。 */
    private static final char[] WINDOWS_1252 = {
            '€', 0, '‚', 'ƒ', '„', '…', '†', '‡',
            'ˆ', '‰', 'Š', '‹', 'Œ', 0, 'Ž', 0,
            0, '‘', '’', '“', '”', '•', '–', '—',
            '˜', '™', 'š', '›', 'œ', 0, 'ž', 'Ÿ'};

    /** 最长的 HTML5 命名引用（{@code CounterClockwiseContourIntegral}）为 31 个字符。 */
    private static final int MAX_NAME_LENGTH = 32;

    private LegacyTextEntities() {
    }

    /** 解码一次以分号结尾的字符引用；null 视为空串。 */
    public static String decodeOnce(String raw) {
        if (raw == null) {
            return "";
        }
        int amp = raw.indexOf('&');
        if (amp < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        out.append(raw, 0, amp);
        int i = amp;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int end = i + 1 < n && raw.charAt(i + 1) == '#' ? numeric(raw, i, out) : named(raw, i, out);
            if (end < 0) {
                out.append('&');
                i++;
            } else {
                i = end;
            }
        }
        return out.toString();
    }

    /** 解码 {@code &#…;}，成功时写入 out 并返回引用之后的位置，否则返回 -1。 */
    private static int numeric(String s, int amp, StringBuilder out) {
        int n = s.length();
        int j = amp + 2;
        boolean hex = j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
        if (hex) {
            j++;
        }
        int start = j;
        long value = 0;
        while (j < n && s.charAt(j) < 0x80 && Character.digit(s.charAt(j), hex ? 16 : 10) >= 0) {
            value = Math.min(value * (hex ? 16 : 10) + Character.digit(s.charAt(j), 16), 0x110000L);
            j++;
        }
        if (j == start || j >= n || s.charAt(j) != ';') {
            return -1;
        }
        out.appendCodePoint(codePoint(value));
        return j + 1;
    }

    private static int codePoint(long value) {
        if (value == 0 || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
            return 0xFFFD;
        }
        int cp = (int) value;
        if (cp >= 0x80 && cp <= 0x9F && WINDOWS_1252[cp - 0x80] != 0) {
            return WINDOWS_1252[cp - 0x80];
        }
        if (Character.isISOControl(cp) && cp != '\t' && cp != '\n' && cp != '\f' && cp != '\r') {
            return 0xFFFD;
        }
        return cp;
    }

    /** 解码 {@code &name;}，成功时写入 out 并返回引用之后的位置，否则返回 -1。 */
    private static int named(String s, int amp, StringBuilder out) {
        int n = s.length();
        int j = amp + 1;
        while (j < n && j - amp <= MAX_NAME_LENGTH && isAsciiAlphanumeric(s.charAt(j))) {
            j++;
        }
        if (j == amp + 1 || j >= n || s.charAt(j) != ';') {
            return -1;
        }
        String name = s.substring(amp + 1, j);
        if (!Entities.isNamedEntity(name)) {
            return -1;
        }
        out.append(Entities.getByName(name));
        return j + 1;
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}

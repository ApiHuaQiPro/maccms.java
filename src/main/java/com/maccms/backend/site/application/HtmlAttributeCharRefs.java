package com.maccms.backend.site.application;

import java.util.Optional;
import java.util.Set;

/**
 * 按浏览器解析 HTML 属性值的规则解码字符引用（WHATWG HTML「character reference state」，属性值分支），只支持一个子集。
 * 旧 PHP 把图片地址原样（仅转义引号、尖括号）放进 {@code src="…"}，浏览器会解码其中的字符引用；
 * 新版在校验前做同样的解码，再交给模板按属性转义输出，浏览器最终请求的地址与旧版一致。
 *
 * <ul>
 * <li>数字引用 {@code &#NN;}、{@code &#xHH;}（分号可省略）：解码；0、超出 U+10FFFF、代理区、0x80～0x9F
 * （浏览器按 windows-1252 重映射）不支持。</li>
 * <li>带分号的命名引用：只支持 {@code amp}、{@code quot}、{@code lt}、{@code gt}（含全大写）与 {@code apos}，
 * 其他名称不内置实体表，不支持。</li>
 * <li>不带分号的命名引用：浏览器只解码 {@link #LEGACY_NAMES} 中的名称，且在属性值里后面紧跟 {@code =} 时不解码；
 * 名称后面接字母或数字时不算该名称。其中只支持 {@code amp}/{@code quot}/{@code lt}/{@code gt}，其他会被解码的不支持。</li>
 * <li>其他 {@code &}：原样。</li>
 * </ul>
 * 遇到不支持的引用时返回空，由调用方拒绝整个地址。
 */
final class HtmlAttributeCharRefs {

    /** HTML 规范中可以不带分号的 106 个命名引用（与 jsoup 的 base 实体集一致，测试核对）。 */
    static final Set<String> LEGACY_NAMES = Set.of(
            "AElig", "AMP", "Aacute", "Acirc", "Agrave", "Aring", "Atilde", "Auml", "COPY", "Ccedil", "ETH", "Eacute",
            "Ecirc", "Egrave", "Euml", "GT", "Iacute", "Icirc", "Igrave", "Iuml", "LT", "Ntilde", "Oacute", "Ocirc",
            "Ograve", "Oslash", "Otilde", "Ouml", "QUOT", "REG", "THORN", "Uacute", "Ucirc", "Ugrave", "Uuml", "Yacute",
            "aacute", "acirc", "acute", "aelig", "agrave", "amp", "aring", "atilde", "auml", "brvbar", "ccedil", "cedil",
            "cent", "copy", "curren", "deg", "divide", "eacute", "ecirc", "egrave", "eth", "euml", "frac12", "frac14",
            "frac34", "gt", "iacute", "icirc", "iexcl", "igrave", "iquest", "iuml", "laquo", "lt", "macr", "micro",
            "middot", "nbsp", "not", "ntilde", "oacute", "ocirc", "ograve", "ordf", "ordm", "oslash", "otilde", "ouml",
            "para", "plusmn", "pound", "quot", "raquo", "reg", "sect", "shy", "sup1", "sup2", "sup3", "szlig", "thorn",
            "times", "uacute", "ucirc", "ugrave", "uml", "uuml", "yacute", "yen", "yuml");

    private HtmlAttributeCharRefs() {
    }

    static Optional<String> decode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < n && s.charAt(i + 1) == '#') {
                int j = i + 2;
                boolean hex = j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
                if (hex) {
                    j++;
                }
                int start = j;
                long value = 0;
                while (j < n && Character.digit(s.charAt(j), hex ? 16 : 10) >= 0 && s.charAt(j) < 0x80) {
                    value = Math.min(value * (hex ? 16 : 10) + Character.digit(s.charAt(j), 16), 0x110000L);
                    j++;
                }
                if (j == start) {
                    // "&#" / "&#x" 后面没有数字：不是字符引用，原样
                    out.append('&');
                    i++;
                    continue;
                }
                if (j < n && s.charAt(j) == ';') {
                    j++;
                }
                if (value == 0 || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)
                        || (value >= 0x80 && value <= 0x9F)) {
                    return Optional.empty();
                }
                out.appendCodePoint((int) value);
                i = j;
                continue;
            }
            int j = i + 1;
            while (j < n && isAsciiAlphanumeric(s.charAt(j))) {
                j++;
            }
            String name = s.substring(i + 1, j);
            if (name.isEmpty()) {
                out.append('&');
                i++;
                continue;
            }
            if (j < n && s.charAt(j) == ';') {
                if (name.equals("apos")) {
                    out.append('\'');
                } else {
                    char basic = basic(name);
                    if (basic == 0) {
                        return Optional.empty();
                    }
                    out.append(basic);
                }
                i = j + 1;
                continue;
            }
            if (LEGACY_NAMES.contains(name) && !(j < n && s.charAt(j) == '=')) {
                char basic = basic(name);
                if (basic == 0) {
                    return Optional.empty();
                }
                out.append(basic);
            } else {
                out.append('&').append(name);
            }
            i = j;
        }
        return Optional.of(out.toString());
    }

    private static char basic(String name) {
        return switch (name) {
            case "amp", "AMP" -> '&';
            case "quot", "QUOT" -> '"';
            case "lt", "LT" -> '<';
            case "gt", "GT" -> '>';
            default -> 0;
        };
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}

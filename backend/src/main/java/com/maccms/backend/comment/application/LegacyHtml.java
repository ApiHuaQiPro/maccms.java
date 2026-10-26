package com.maccms.backend.comment.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论与留言写入用到的旧 PHP 文本函数（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 4.2 节），与 PHP 7.3/8.0 逐字节相同
 * （黄金向量 {@code LegacyHtmlGoldenTest}）：
 * <ul>
 * <li>{@link #htmlentities}：PHP 7.3/8.0 默认参数（{@code ENT_COMPAT | ENT_HTML401}，UTF-8，double_encode）——HTML 4.01 的 252 个命名实体
 * （表取自两个版本的 {@code get_html_translation_table}，完全相同），{@code "} 转换、单引号不转换；输入不是合法 UTF-8 时返回空串。</li>
 * <li>{@link #filterXss}：{@code mac_filter_xss}（application/common.php:1222-1225）{@code trim(htmlspecialchars(strip_tags($s), ENT_QUOTES))}。
 * 这里的输入都已经过 htmlentities（不含 {@code <}），strip_tags 只会去掉 NUL 字节。</li>
 * <li>{@link #filterWords}：{@code mac_filter_words}（:1457-1471）按 {@code app.filter_words} 的各项依次 str_replace 成 {@code ***}（区分大小写，空项跳过）。</li>
 * <li>{@link #restoreHtmlFilter}：{@code mac_restore_htmlfilter}（:1227-1232）含 {@code &amp;amp;} 时 {@code htmlspecialchars_decode(ENT_QUOTES)}，列表显示用。</li>
 * </ul>
 */
public final class LegacyHtml {

    private static final Object[] ENTITIES = {
            0x0022, "&quot;", 0x0026, "&amp;", 0x003C, "&lt;", 0x003E, "&gt;",
            0x00A0, "&nbsp;", 0x00A1, "&iexcl;", 0x00A2, "&cent;", 0x00A3, "&pound;",
            0x00A4, "&curren;", 0x00A5, "&yen;", 0x00A6, "&brvbar;", 0x00A7, "&sect;",
            0x00A8, "&uml;", 0x00A9, "&copy;", 0x00AA, "&ordf;", 0x00AB, "&laquo;",
            0x00AC, "&not;", 0x00AD, "&shy;", 0x00AE, "&reg;", 0x00AF, "&macr;",
            0x00B0, "&deg;", 0x00B1, "&plusmn;", 0x00B2, "&sup2;", 0x00B3, "&sup3;",
            0x00B4, "&acute;", 0x00B5, "&micro;", 0x00B6, "&para;", 0x00B7, "&middot;",
            0x00B8, "&cedil;", 0x00B9, "&sup1;", 0x00BA, "&ordm;", 0x00BB, "&raquo;",
            0x00BC, "&frac14;", 0x00BD, "&frac12;", 0x00BE, "&frac34;", 0x00BF, "&iquest;",
            0x00C0, "&Agrave;", 0x00C1, "&Aacute;", 0x00C2, "&Acirc;", 0x00C3, "&Atilde;",
            0x00C4, "&Auml;", 0x00C5, "&Aring;", 0x00C6, "&AElig;", 0x00C7, "&Ccedil;",
            0x00C8, "&Egrave;", 0x00C9, "&Eacute;", 0x00CA, "&Ecirc;", 0x00CB, "&Euml;",
            0x00CC, "&Igrave;", 0x00CD, "&Iacute;", 0x00CE, "&Icirc;", 0x00CF, "&Iuml;",
            0x00D0, "&ETH;", 0x00D1, "&Ntilde;", 0x00D2, "&Ograve;", 0x00D3, "&Oacute;",
            0x00D4, "&Ocirc;", 0x00D5, "&Otilde;", 0x00D6, "&Ouml;", 0x00D7, "&times;",
            0x00D8, "&Oslash;", 0x00D9, "&Ugrave;", 0x00DA, "&Uacute;", 0x00DB, "&Ucirc;",
            0x00DC, "&Uuml;", 0x00DD, "&Yacute;", 0x00DE, "&THORN;", 0x00DF, "&szlig;",
            0x00E0, "&agrave;", 0x00E1, "&aacute;", 0x00E2, "&acirc;", 0x00E3, "&atilde;",
            0x00E4, "&auml;", 0x00E5, "&aring;", 0x00E6, "&aelig;", 0x00E7, "&ccedil;",
            0x00E8, "&egrave;", 0x00E9, "&eacute;", 0x00EA, "&ecirc;", 0x00EB, "&euml;",
            0x00EC, "&igrave;", 0x00ED, "&iacute;", 0x00EE, "&icirc;", 0x00EF, "&iuml;",
            0x00F0, "&eth;", 0x00F1, "&ntilde;", 0x00F2, "&ograve;", 0x00F3, "&oacute;",
            0x00F4, "&ocirc;", 0x00F5, "&otilde;", 0x00F6, "&ouml;", 0x00F7, "&divide;",
            0x00F8, "&oslash;", 0x00F9, "&ugrave;", 0x00FA, "&uacute;", 0x00FB, "&ucirc;",
            0x00FC, "&uuml;", 0x00FD, "&yacute;", 0x00FE, "&thorn;", 0x00FF, "&yuml;",
            0x0152, "&OElig;", 0x0153, "&oelig;", 0x0160, "&Scaron;", 0x0161, "&scaron;",
            0x0178, "&Yuml;", 0x0192, "&fnof;", 0x02C6, "&circ;", 0x02DC, "&tilde;",
            0x0391, "&Alpha;", 0x0392, "&Beta;", 0x0393, "&Gamma;", 0x0394, "&Delta;",
            0x0395, "&Epsilon;", 0x0396, "&Zeta;", 0x0397, "&Eta;", 0x0398, "&Theta;",
            0x0399, "&Iota;", 0x039A, "&Kappa;", 0x039B, "&Lambda;", 0x039C, "&Mu;",
            0x039D, "&Nu;", 0x039E, "&Xi;", 0x039F, "&Omicron;", 0x03A0, "&Pi;",
            0x03A1, "&Rho;", 0x03A3, "&Sigma;", 0x03A4, "&Tau;", 0x03A5, "&Upsilon;",
            0x03A6, "&Phi;", 0x03A7, "&Chi;", 0x03A8, "&Psi;", 0x03A9, "&Omega;",
            0x03B1, "&alpha;", 0x03B2, "&beta;", 0x03B3, "&gamma;", 0x03B4, "&delta;",
            0x03B5, "&epsilon;", 0x03B6, "&zeta;", 0x03B7, "&eta;", 0x03B8, "&theta;",
            0x03B9, "&iota;", 0x03BA, "&kappa;", 0x03BB, "&lambda;", 0x03BC, "&mu;",
            0x03BD, "&nu;", 0x03BE, "&xi;", 0x03BF, "&omicron;", 0x03C0, "&pi;",
            0x03C1, "&rho;", 0x03C2, "&sigmaf;", 0x03C3, "&sigma;", 0x03C4, "&tau;",
            0x03C5, "&upsilon;", 0x03C6, "&phi;", 0x03C7, "&chi;", 0x03C8, "&psi;",
            0x03C9, "&omega;", 0x03D1, "&thetasym;", 0x03D2, "&upsih;", 0x03D6, "&piv;",
            0x2002, "&ensp;", 0x2003, "&emsp;", 0x2009, "&thinsp;", 0x200C, "&zwnj;",
            0x200D, "&zwj;", 0x200E, "&lrm;", 0x200F, "&rlm;", 0x2013, "&ndash;",
            0x2014, "&mdash;", 0x2018, "&lsquo;", 0x2019, "&rsquo;", 0x201A, "&sbquo;",
            0x201C, "&ldquo;", 0x201D, "&rdquo;", 0x201E, "&bdquo;", 0x2020, "&dagger;",
            0x2021, "&Dagger;", 0x2022, "&bull;", 0x2026, "&hellip;", 0x2030, "&permil;",
            0x2032, "&prime;", 0x2033, "&Prime;", 0x2039, "&lsaquo;", 0x203A, "&rsaquo;",
            0x203E, "&oline;", 0x2044, "&frasl;", 0x20AC, "&euro;", 0x2111, "&image;",
            0x2118, "&weierp;", 0x211C, "&real;", 0x2122, "&trade;", 0x2135, "&alefsym;",
            0x2190, "&larr;", 0x2191, "&uarr;", 0x2192, "&rarr;", 0x2193, "&darr;",
            0x2194, "&harr;", 0x21B5, "&crarr;", 0x21D0, "&lArr;", 0x21D1, "&uArr;",
            0x21D2, "&rArr;", 0x21D3, "&dArr;", 0x21D4, "&hArr;", 0x2200, "&forall;",
            0x2202, "&part;", 0x2203, "&exist;", 0x2205, "&empty;", 0x2207, "&nabla;",
            0x2208, "&isin;", 0x2209, "&notin;", 0x220B, "&ni;", 0x220F, "&prod;",
            0x2211, "&sum;", 0x2212, "&minus;", 0x2217, "&lowast;", 0x221A, "&radic;",
            0x221D, "&prop;", 0x221E, "&infin;", 0x2220, "&ang;", 0x2227, "&and;",
            0x2228, "&or;", 0x2229, "&cap;", 0x222A, "&cup;", 0x222B, "&int;",
            0x2234, "&there4;", 0x223C, "&sim;", 0x2245, "&cong;", 0x2248, "&asymp;",
            0x2260, "&ne;", 0x2261, "&equiv;", 0x2264, "&le;", 0x2265, "&ge;",
            0x2282, "&sub;", 0x2283, "&sup;", 0x2284, "&nsub;", 0x2286, "&sube;",
            0x2287, "&supe;", 0x2295, "&oplus;", 0x2297, "&otimes;", 0x22A5, "&perp;",
            0x22C5, "&sdot;", 0x2308, "&lceil;", 0x2309, "&rceil;", 0x230A, "&lfloor;",
            0x230B, "&rfloor;", 0x2329, "&lang;", 0x232A, "&rang;", 0x25CA, "&loz;",
            0x2660, "&spades;", 0x2663, "&clubs;", 0x2665, "&hearts;", 0x2666, "&diams;"
    };
    private static final Map<Integer, String> BY_CODE_POINT = new HashMap<>();

    static {
        for (int i = 0; i < ENTITIES.length; i += 2) {
            BY_CODE_POINT.put((Integer) ENTITIES[i], (String) ENTITIES[i + 1]);
        }
    }

    private LegacyHtml() {
    }

    /** 实体表的条目数（PHP 7.3/8.0 均为 252）。 */
    static int entityCount() {
        return BY_CODE_POINT.size();
    }

    /** PHP {@code htmlentities($bytes)}；不是合法 UTF-8 时为空串。 */
    public static String htmlentities(byte[] utf8) {
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ent = BY_CODE_POINT.get(cp);
            if (ent != null) {
                sb.append(ent);
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** {@code mac_filter_xss($s)}：去掉 NUL（strip_tags）→ {@code htmlspecialchars(ENT_QUOTES)} → trim。 */
    public static String filterXss(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\0' -> {
                }
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#039;");
                default -> sb.append(c);
            }
        }
        return phpTrim(sb.toString());
    }

    /**
     * {@code mac_filter_words($bytes)}：按字节对每个过滤词依次替换（PHP {@code str_replace} 的数组形式：从左到右、不重叠，后一个词作用在前一次的结果上）。
     *
     * @param words {@code explode(',', app.filter_words)}
     */
    public static byte[] filterWords(byte[] in, List<byte[]> words) {
        byte[] out = in;
        for (byte[] w : words) {
            if (w.length == 0) {
                continue;
            }
            out = replace(out, w, new byte[] {'*', '*', '*'});
        }
        return out;
    }

    /** {@code mac_restore_htmlfilter($s)}：含 {@code &amp;amp;}（不区分大小写）时按 ENT_QUOTES 解码一次 {@code &amp;amp; &amp;quot; &amp;#039; &amp;lt; &amp;gt;}。 */
    public static String restoreHtmlFilter(String s) {
        if (!s.toLowerCase(java.util.Locale.ROOT).contains("&amp;")) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '&') {
                String hit = null;
                String out = null;
                for (String[] pair : DECODE) {
                    if (s.startsWith(pair[0], i)) {
                        hit = pair[0];
                        out = pair[1];
                        break;
                    }
                }
                if (hit != null) {
                    sb.append(out);
                    i += hit.length();
                    continue;
                }
            }
            sb.append(s.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /**
     * 页面显示的文字（B11）：旧模板原样输出 {@code mac_restore_htmlfilter} 之后的库内值，浏览器再按 HTML 解码一次；新版先做同样的两步，得到浏览器
     * 看到的文字，再由页面模板转义输出。HTML 解码识别 HTML 4.01 的命名实体、{@code &apos;} 与十进制/十六进制字符引用（以分号结尾）；其余原样。
     */
    public static String displayText(String stored) {
        String s = restoreHtmlFilter(stored);
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int semi = c == '&' ? s.indexOf(';', i + 1) : -1;
            if (semi > i + 1 && semi - i <= 12) {
                String ref = s.substring(i + 1, semi);
                Integer cp = reference(ref);
                if (cp != null) {
                    sb.appendCodePoint(cp);
                    i = semi + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static final Map<String, Integer> BY_NAME = new HashMap<>();

    static {
        for (int i = 0; i < ENTITIES.length; i += 2) {
            String e = (String) ENTITIES[i + 1];
            BY_NAME.put(e.substring(1, e.length() - 1), (Integer) ENTITIES[i]);
        }
        BY_NAME.put("apos", (int) '\'');
    }

    private static Integer reference(String ref) {
        if (ref.startsWith("#x") || ref.startsWith("#X")) {
            return codePoint(ref.substring(2), 16);
        }
        if (ref.startsWith("#")) {
            return codePoint(ref.substring(1), 10);
        }
        return BY_NAME.get(ref);
    }

    private static Integer codePoint(String digits, int radix) {
        if (digits.isEmpty() || digits.length() > 7) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), radix) < 0) {
                return null;
            }
        }
        int cp = Integer.parseInt(digits, radix);
        return cp > 0 && cp <= Character.MAX_CODE_POINT && (cp < 0xD800 || cp > 0xDFFF) ? cp : null;
    }

    /** htmlspecialchars_decode(ENT_QUOTES | ENT_HTML401) 识别的写法（含十进制、十六进制的单引号）。 */
    private static final String[][] DECODE = {
        {"&amp;", "&"}, {"&quot;", "\""}, {"&#039;", "'"}, {"&#39;", "'"}, {"&#x27;", "'"}, {"&#X27;", "'"},
        {"&lt;", "<"}, {"&gt;", ">"}
    };

    static String phpTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isTrimChar(s.charAt(a))) {
            a++;
        }
        while (b > a && isTrimChar(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    static byte[] phpTrim(byte[] s) {
        int a = 0;
        int b = s.length;
        while (a < b && isTrimChar((char) (s[a] & 0xff))) {
            a++;
        }
        while (b > a && isTrimChar((char) (s[b - 1] & 0xff))) {
            b--;
        }
        return java.util.Arrays.copyOfRange(s, a, b);
    }

    private static boolean isTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

    private static byte[] replace(byte[] in, byte[] needle, byte[] with) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length);
        int i = 0;
        while (i < in.length) {
            if (i + needle.length <= in.length && matches(in, i, needle)) {
                out.writeBytes(with);
                i += needle.length;
            } else {
                out.write(in[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    private static boolean matches(byte[] in, int at, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (in[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }
}

package com.maccms.backend.content.application;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台内容保存用到的旧 PHP 文本函数（B13，docs/architecture/22-b13-admin-content.md 第 4 节），按字节与 PHP 7.3/8.0 相同
 * （黄金向量 {@code content/admin/text-php80-golden.json}、{@code text-php73-golden.json}，{@code PhpTextGoldenTest}）：
 * {@code strip_tags}、{@code htmlspecialchars(…, ENT_QUOTES)}、{@code trim}、{@code mac_filter_xss}、{@code mb_substr(…, 0, n)}、
 * {@code mac_substring}、正文图片地址的协议替换、{@code mac_restore_htmlfilter}、{@code urldecode}。
 *
 * <p>PHP 字符串是字节串，这里一律按字节处理；调用方在写库前再按 UTF-8 解码（不合法时按非法参数处理）。
 */
public final class PhpText {

    private PhpText() {
    }

    // ------------------------------------------------------------------ strip_tags

    /**
     * PHP {@code strip_tags($s)}（不带允许标签；ext/standard/string.c {@code php_strip_tags_ex}，PHP 7.3 与 8.0 相同）：
     * 五个状态——0 正文、1 标签内、2 {@code <?} 处理指令、3 {@code <!} 声明、4 {@code <!--} 注释；标签内的引号使 {@code >} 不结束标签，
     * {@code <} 后紧跟空白时按正文输出，NUL 一律去掉。
     */
    public static byte[] stripTags(byte[] buf) {
        int len = buf.length;
        byte[] out = new byte[len];
        int rp = 0;
        int depth = 0;
        int inQ = 0;
        int br = 0;
        int lc = 0;
        boolean isXml = false;
        int state = 0;
        int p = 0;
        while (p < len) {
            int c = buf[p] & 0xff;
            switch (state) {
                case 0 -> {
                    switch (c) {
                        case 0 -> {
                        }
                        case '<' -> {
                            if (inQ != 0) {
                                break;
                            }
                            if (isSpace(at(buf, p + 1))) {
                                out[rp++] = (byte) c;
                                break;
                            }
                            lc = '<';
                            state = 1;
                        }
                        case '>' -> {
                            if (depth > 0) {
                                depth--;
                                break;
                            }
                            if (inQ != 0) {
                                break;
                            }
                            out[rp++] = (byte) c;
                        }
                        default -> out[rp++] = (byte) c;
                    }
                }
                case 1 -> {
                    switch (c) {
                        case 0 -> {
                        }
                        case '<' -> {
                            if (inQ != 0) {
                                break;
                            }
                            if (isSpace(at(buf, p + 1))) {
                                break;
                            }
                            depth++;
                        }
                        case '>' -> {
                            if (depth > 0) {
                                depth--;
                                break;
                            }
                            if (inQ != 0) {
                                break;
                            }
                            lc = '>';
                            if (isXml && p >= 1 && buf[p - 1] == '-') {
                                break;
                            }
                            inQ = 0;
                            state = 0;
                            isXml = false;
                        }
                        case '"', '\'' -> {
                            if (p != 0 && (inQ == 0 || c == inQ)) {
                                inQ = inQ != 0 ? 0 : c;
                            }
                        }
                        case '!' -> {
                            if (p >= 1 && buf[p - 1] == '<') {
                                state = 3;
                                lc = c;
                            }
                        }
                        case '?' -> {
                            if (p >= 1 && buf[p - 1] == '<') {
                                br = 0;
                                state = 2;
                            }
                        }
                        default -> {
                        }
                    }
                }
                case 2 -> {
                    switch (c) {
                        case '(' -> {
                            if (lc != '"' && lc != '\'') {
                                lc = '(';
                                br++;
                            }
                        }
                        case ')' -> {
                            if (lc != '"' && lc != '\'') {
                                lc = ')';
                                br--;
                            }
                        }
                        case '>' -> {
                            if (depth > 0) {
                                depth--;
                                break;
                            }
                            if (inQ != 0) {
                                break;
                            }
                            if (br == 0 && p >= 1 && lc != '"' && buf[p - 1] == '?') {
                                inQ = 0;
                                state = 0;
                            }
                        }
                        case '"', '\'' -> {
                            if (p >= 1 && buf[p - 1] != '\\') {
                                if (lc == c) {
                                    lc = 0;
                                } else if (lc != '\\') {
                                    lc = c;
                                }
                                if (p != 0 && (inQ == 0 || c == inQ)) {
                                    inQ = inQ != 0 ? 0 : c;
                                }
                            }
                        }
                        case 'l', 'L' -> {
                            if (p > 4 && (buf[p - 1] == 'm' || buf[p - 1] == 'M') && (buf[p - 2] == 'x' || buf[p - 2] == 'X')
                                    && buf[p - 3] == '?' && buf[p - 4] == '<') {
                                state = 1;
                                isXml = true;
                            }
                        }
                        default -> {
                        }
                    }
                }
                case 3 -> {
                    switch (c) {
                        case '>' -> {
                            if (depth > 0) {
                                depth--;
                                break;
                            }
                            if (inQ != 0) {
                                break;
                            }
                            inQ = 0;
                            state = 0;
                        }
                        case '"', '\'' -> {
                            if (p != 0 && buf[p - 1] != '\\' && (inQ == 0 || c == inQ)) {
                                inQ = inQ != 0 ? 0 : c;
                            }
                        }
                        case '-' -> {
                            if (p >= 2 && buf[p - 1] == '-' && buf[p - 2] == '!') {
                                state = 4;
                            }
                        }
                        case 'E', 'e' -> {
                            if (p > 6 && lower(buf[p - 1]) == 'p' && lower(buf[p - 2]) == 'y' && lower(buf[p - 3]) == 't'
                                    && lower(buf[p - 4]) == 'c' && lower(buf[p - 5]) == 'o' && lower(buf[p - 6]) == 'd') {
                                state = 1;
                            }
                        }
                        default -> {
                        }
                    }
                }
                default -> {
                    if (c == '>' && inQ == 0 && p >= 2 && buf[p - 1] == '-' && buf[p - 2] == '-') {
                        inQ = 0;
                        state = 0;
                    }
                }
            }
            p++;
        }
        return Arrays.copyOf(out, rp);
    }

    /** C 的 {@code isspace}（C 区域）。 */
    private static boolean isSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == '\f';
    }

    /** 越界时为 0（PHP 字符串末尾的 NUL）。 */
    private static int at(byte[] b, int i) {
        return i < b.length ? b[i] & 0xff : 0;
    }

    private static int lower(byte b) {
        return b >= 'A' && b <= 'Z' ? b + 32 : b;
    }

    // ------------------------------------------------------------------ htmlspecialchars / trim / mac_filter_xss

    /** PHP {@code htmlspecialchars($s, ENT_QUOTES)}（UTF-8，double_encode）：不是合法 UTF-8 时返回空串。 */
    public static byte[] htmlspecialchars(byte[] s) {
        if (!validUtf8(s)) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length + 16);
        for (byte b : s) {
            switch (b) {
                case '&' -> out.writeBytes(AMP);
                case '"' -> out.writeBytes(QUOT);
                case '\'' -> out.writeBytes(APOS);
                case '<' -> out.writeBytes(LT);
                case '>' -> out.writeBytes(GT);
                default -> out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static final byte[] AMP = "&amp;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUOT = "&quot;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] APOS = "&#039;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LT = "&lt;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GT = "&gt;".getBytes(StandardCharsets.US_ASCII);

    /** PHP {@code trim($s)}：两端去掉空格、\t、\n、\r、\0、\x0B。 */
    public static byte[] trim(byte[] s) {
        int a = 0;
        int b = s.length;
        while (a < b && isTrim(s[a])) {
            a++;
        }
        while (b > a && isTrim(s[b - 1])) {
            b--;
        }
        return a == 0 && b == s.length ? s : Arrays.copyOfRange(s, a, b);
    }

    private static boolean isTrim(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }

    /** {@code mac_filter_xss($s)}（application/common.php:1222-1225）：{@code trim(htmlspecialchars(strip_tags($s), ENT_QUOTES))}。 */
    public static byte[] filterXss(byte[] s) {
        return trim(htmlspecialchars(stripTags(s)));
    }


    /** PHP 7.3/8.0 {@code htmlspecialchars($s)} 的默认参数（{@code ENT_COMPAT}）：只转 {@code & " < >}，单引号不变；非法 UTF-8 时为空串。 */
    public static byte[] htmlspecialcharsCompat(byte[] s) {
        if (!validUtf8(s)) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length + 16);
        for (byte b : s) {
            switch (b) {
                case '&' -> out.writeBytes(AMP);
                case '"' -> out.writeBytes(QUOT);
                case '<' -> out.writeBytes(LT);
                case '>' -> out.writeBytes(GT);
                default -> out.write(b);
            }
        }
        return out.toByteArray();
    }

    /**
     * 旧版 {@code LIKE '%词%'}（未转义：{@code %}、{@code _} 是通配符，反斜线让下一个字符按字面量匹配）转写成 {@code ESCAPE '!'} 下
     * 匹配同样字符串的模式（与 B12 管理员列表、B11 视频搜索缓存相同）。词以反斜线结尾时，旧版补在末尾的 {@code %} 也变成字面量。
     */
    public static String legacyLike(String word) {
        StringBuilder sb = new StringBuilder(word.length() + 4).append('%');
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '\\' && i + 1 == word.length()) {
                return sb.append("!%").toString();
            } else if (c == '\\') {
                char next = word.charAt(++i);
                sb.append('!').append(next);
                if (Character.isHighSurrogate(next) && i + 1 < word.length()) {
                    sb.append(word.charAt(++i));
                }
            } else if (c == '!') {
                sb.append("!!");
            } else {
                sb.append(c);
            }
        }
        return sb.append('%').toString();
    }

    // ------------------------------------------------------------------ mb_substr / mac_substring

    /**
     * PHP {@code mb_substr($s, 0, $n)}（UTF-8）：按 mbstring 的 UTF-8 字节长度表逐个字符前进（0x00-0x7F、0x80-0xBF 为 1，0xC0-0xDF 为 2，
     * 0xE0-0xEF 为 3，0xF0-0xF7 为 4，0xF8-0xFB 为 5，0xFC-0xFD 为 6，其余为 1），取前 {@code n} 个；不检查后续字节是否合法。
     */
    public static byte[] mbSubstr(byte[] s, int n) {
        int i = 0;
        int count = 0;
        while (i < s.length && count < n) {
            i += mbLen(s[i] & 0xff);
            count++;
        }
        return Arrays.copyOf(s, Math.min(i, s.length));
    }

    private static int mbLen(int b) {
        if (b < 0xC0) {
            return 1;
        }
        if (b < 0xE0) {
            return 2;
        }
        if (b < 0xF0) {
            return 3;
        }
        if (b < 0xF8) {
            return 4;
        }
        if (b < 0xFC) {
            return 5;
        }
        if (b < 0xFE) {
            return 6;
        }
        return 1;
    }

    /**
     * {@code mac_substring($s, $n)}（application/common.php:637-679）按 PHP 7.3 的结果：按首字节的二进制前缀前进（0 开头 1 字节、110 开头
     * 2 字节、1110 开头 3 字节，其余字节——续字节、4 字节字符的首字节——各换成一个空格），取前 {@code n} 个。
     * <p>PHP 8 下旧代码用 {@code ' ' == 0} 判断单字节字符，结果为假，ord 64-127 的字符（字母等）都被换成空格（14 文档第 6 节已记录）；
     * 新版不复刻这一版本缺陷（22 文档 D-B13-5）。
     */
    public static byte[] macSubstring(byte[] s, int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(s.length, n * 3));
        int m = 0;
        for (int i = 0; i < s.length && n > 0; i++) {
            int b = s[i] & 0xff;
            if (b < 0x80) {
                out.write(b);
            } else if ((b & 0xE0) == 0xC0) {
                out.write(s, i, Math.min(2, s.length - i));
                i += 1;
            } else if ((b & 0xF0) == 0xE0) {
                out.write(s, i, Math.min(3, s.length - i));
                i += 2;
            } else {
                out.write(' ');
            }
            if (++m >= n) {
                break;
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 正文图片协议、restore、urldecode

    /** 旧模型保存正文时找图片地址的正则（按字节，不带 /s 与 /u）：{@code /<img[\s\S]*?src\s*=\s*[\"|\'](.*?)[\"|\'][\s\S]*?>/}。 */
    private static final Pattern IMG_SRC = Pattern.compile("<img[\\s\\S]*?src\\s*=\\s*[\"|'](.*?)[\"|'][\\s\\S]*?>",
            Pattern.UNIX_LINES);

    /**
     * 正文图片地址的协议替换（common/model/Vod.php:651-661，Art/Topic/Actor/Role 相同）：对 {@code preg_match_all} 找到的每个地址
     * {@code $v1}，把正文里所有 {@code $v1} 换成 {@code str_replace(protocol . ':', 'mac:', $v1)}。
     */
    public static byte[] contentImageProtocol(byte[] content, String protocol) {
        String s = latin1(content);
        Matcher m = IMG_SRC.matcher(s);
        java.util.List<String> found = new java.util.ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        String needle = latin1((protocol + ":").getBytes(StandardCharsets.UTF_8));
        for (String v1 : found) {
            String v2 = strReplace(v1, needle, "mac:");
            s = strReplace(s, v1, v2);
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 正文里第一张图片的地址（Art::saveData 在 art_pic 为空时取 {@code $match_src1[1][0]}）；没有时为 null。 */
    public static byte[] firstImageSrc(byte[] content) {
        Matcher m = IMG_SRC.matcher(latin1(content));
        return m.find() ? m.group(1).getBytes(StandardCharsets.ISO_8859_1) : null;
    }

    /** PHP {@code str_replace($search, $replace, $subject)}（单个字符串）：空的 search 原样返回。 */
    static String strReplace(String subject, String search, String replace) {
        return search.isEmpty() ? subject : subject.replace(search, replace);
    }

    /** PHP {@code str_replace} 按字节。 */
    public static byte[] strReplace(byte[] subject, byte[] search, byte[] replace) {
        return strReplace(latin1(subject), latin1(search), latin1(replace)).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * {@code mac_restore_htmlfilter($s)}（application/common.php:1227-1232）：含 {@code &amp;amp;}（不区分大小写）时
     * {@code htmlspecialchars_decode($s, ENT_QUOTES)}（HTML 4.01）：解码 {@code &amp;amp; &amp;quot; &amp;lt; &amp;gt;}（区分大小写）
     * 与表示 {@code & " ' < >} 的十进制、十六进制字符引用（{@code &amp;#039;}、{@code &amp;#x27;} 等，必须以分号结尾）。
     */
    public static byte[] restoreHtmlFilter(byte[] s) {
        String t = latin1(s);
        if (!t.toLowerCase(java.util.Locale.ROOT).contains("&amp;")) {
            return s;
        }
        StringBuilder sb = new StringBuilder(t.length());
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '&') {
                int semi = t.indexOf(';', i + 1);
                if (semi > i + 1) {
                    String ref = t.substring(i + 1, semi);
                    Character d = specialRef(ref);
                    if (d != null) {
                        sb.append(d.charValue());
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Character specialRef(String ref) {
        switch (ref) {
            case "amp":
                return '&';
            case "quot":
                return '"';
            case "lt":
                return '<';
            case "gt":
                return '>';
            default:
                break;
        }
        if (ref.length() < 2 || ref.charAt(0) != '#') {
            return null;
        }
        long cp;
        try {
            if (ref.charAt(1) == 'x' || ref.charAt(1) == 'X') {
                String hex = ref.substring(2);
                if (hex.isEmpty() || !hex.chars().allMatch(ch -> Character.digit(ch, 16) >= 0) || hex.length() > 16) {
                    return null;
                }
                cp = Long.parseLong(hex, 16);
            } else {
                String dec = ref.substring(1);
                if (dec.isEmpty() || !dec.chars().allMatch(ch -> ch >= '0' && ch <= '9') || dec.length() > 18) {
                    return null;
                }
                cp = Long.parseLong(dec);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return cp == '&' || cp == '"' || cp == '\'' || cp == '<' || cp == '>' ? Character.valueOf((char) cp) : null;
    }

    /** PHP {@code urldecode($s)}：{@code +} 变空格，{@code %XX}（十六进制，不分大小写）变字节，其他原样。 */
    public static byte[] urldecode(byte[] s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length);
        for (int i = 0; i < s.length; i++) {
            byte b = s[i];
            if (b == '+') {
                out.write(' ');
            } else if (b == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
                out.write(hex(s[i + 1]) * 16 + hex(s[i + 2]));
                i += 2;
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static int hex(byte b) {
        return Character.digit((char) (b & 0xff), 16);
    }

    // ------------------------------------------------------------------ 公共

    public static boolean validUtf8(byte[] s) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(s));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}

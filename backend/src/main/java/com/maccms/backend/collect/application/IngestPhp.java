package com.maccms.backend.collect.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.PhpText;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * 采集入库（B19，27 文档第 3 节）用到的旧 PHP 函数与 PHP 8 的值语义。值的表示同 {@link ProvideValues}（字符串、{@link Long}、
 * {@link Double}、{@link Boolean}、{@code null}、数组）；入库前的文字都是合法 UTF-8（Receive 的非法字节在入口拒绝，Provide 文件的 JSON
 * 解码本身要求合法 UTF-8）。
 */
public final class IngestPhp {

    /** PHP 8 {@code is_numeric}：前导空白、可选符号、十进制（可带小数点）、可选指数、尾随空白。 */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\x0B\\f]*");
    private static final Pattern FLOAT_PREFIX = Pattern.compile(
            "^[ \\t\\n\\r\\x0B\\f]*([+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?)");

    private IngestPhp() {
    }

    /** {@code (string)$v}。 */
    public static String str(Object v) {
        return ProvideValues.str(v);
    }

    /** {@code empty($v)}。 */
    public static boolean empty(Object v) {
        return ProvideValues.empty(v);
    }

    /** {@code intval($v)}。 */
    public static long intval(Object v) {
        return switch (v) {
            case null -> 0;
            case Long l -> l;
            case Double d -> Double.isFinite(d) ? (long) d.doubleValue() : 0;
            case Boolean b -> b ? 1 : 0;
            case String s -> PhpIntval.of(s);
            case Map<?, ?> m -> m.isEmpty() ? 0 : 1;
            default -> 0;
        };
    }

    /** {@code floatval($v)}。 */
    public static double floatval(Object v) {
        return switch (v) {
            case null -> 0;
            case Long l -> l;
            case Double d -> d;
            case Boolean b -> b ? 1 : 0;
            case String s -> {
                java.util.regex.Matcher m = FLOAT_PREFIX.matcher(s);
                yield m.find() ? Double.parseDouble(m.group(1)) : 0;
            }
            case Map<?, ?> m -> m.isEmpty() ? 0 : 1;
            default -> 0;
        };
    }

    public static boolean isNumeric(String s) {
        return s != null && NUMERIC.matcher(s).matches();
    }

    /**
     * PHP 8 {@code $a == $b}，{@code b} 是库里读出的字符串（或 null）：两边都是数字（数字串）时按数值比较；整数、浮点与非数字串比较时把数字转成
     * 字符串再逐字节比较；null 与字符串比较时把 null 当作空串，与其他值比较时两边按布尔值比较。
     */
    public static boolean looseEquals(Object a, String b) {
        if (b == null) {
            return a instanceof String s ? s.isEmpty() : empty(a);
        }
        return switch (a) {
            case null -> b.isEmpty();
            case String s -> isNumeric(s) && isNumeric(b) ? numericEquals(s, b) : s.equals(b);
            case Long l -> isNumeric(b) ? numericEquals(Long.toString(l), b) : Long.toString(l).equals(b);
            case Double d -> isNumeric(b) ? d == Double.parseDouble(b.strip()) : str(d).equals(b);
            case Boolean bool -> bool == !(b.isEmpty() || b.equals("0"));
            default -> false;
        };
    }

    /** 两个数字串：都是 int64 范围内的整数时按整数比较，否则按浮点比较。 */
    private static boolean numericEquals(String a, String b) {
        Long x = longOrNull(a.strip());
        Long y = longOrNull(b.strip());
        if (x != null && y != null) {
            return x.longValue() == y.longValue();
        }
        return Double.parseDouble(a.strip()) == Double.parseDouble(b.strip());
    }

    private static Long longOrNull(String s) {
        if (!s.matches("[+-]?\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(s.startsWith("+") ? s.substring(1) : s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 文本函数

    /** {@code strip_tags($v)}（非数组的值先转成字符串）。 */
    public static String stripTags(Object v) {
        return utf8(PhpText.stripTags(bytes(str(v))));
    }

    /** {@code mac_filter_xss($s)} = {@code trim(htmlspecialchars(strip_tags($s), ENT_QUOTES))}。 */
    public static String filterXss(Object v) {
        return utf8(PhpText.filterXss(bytes(str(v))));
    }

    /** {@code mb_substr($s, 0, $n)}。 */
    public static String mbSubstr(String s, int n) {
        return utf8(PhpText.mbSubstr(bytes(s), n));
    }

    /** {@code mac_substring($s, $n)}（PHP 7.3 的结果，同 B13 D-B13-5）。 */
    public static String macSubstring(String s, int n) {
        return macSubstring(s, n, 0);
    }

    /**
     * {@code mac_substring($s, $n, $start)}（application/common.php:637-679，PHP 7.3 的结果）：先按首字节跳过 {@code start} 个字符，
     * 再取 {@code n} 个；首字节 0、110、1110 开头分别是 1、2、3 字节，其余字节各换成一个空格。
     */
    public static String macSubstring(String s, int n, int start) {
        byte[] b = bytes(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int skipped = 0;
        int m = 0;
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if (skipped < start) {
                if ((c & 0xE0) == 0xC0) {
                    i += 1;
                } else if ((c & 0xF0) == 0xE0) {
                    i += 2;
                }
                skipped++;
                continue;
            }
            if (c < 0x80) {
                out.write(c);
            } else if ((c & 0xE0) == 0xC0) {
                out.write(b, i, Math.min(2, b.length - i));
                i += 1;
            } else if ((c & 0xF0) == 0xE0) {
                out.write(b, i, Math.min(3, b.length - i));
                i += 2;
            } else {
                out.write(' ');
            }
            if (++m >= n) {
                break;
            }
        }
        return utf8(out.toByteArray());
    }

    /** {@code trim($s, $chars)}（字符表只含单字节字符，不支持 {@code ..} 范围）。 */
    public static String trimChars(String s, String chars) {
        int a = 0;
        int z = s.length();
        while (a < z && chars.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (z > a && chars.indexOf(s.charAt(z - 1)) >= 0) {
            z--;
        }
        return s.substring(a, z);
    }

    /** {@code rtrim($s, $chars)}。 */
    public static String rtrimChars(String s, String chars) {
        int z = s.length();
        while (z > 0 && chars.indexOf(s.charAt(z - 1)) >= 0) {
            z--;
        }
        return s.substring(0, z);
    }

    /** {@code rtrim($s)}（默认字符表：空格、\t、\n、\r、\0、\x0B）。 */
    public static String rtrim(String s) {
        return rtrimChars(s, " \t\n\r\0\u000B");
    }

    /** {@code explode($sep, $s)}（{@code s} 为 null 时按空串，得到一项空串）。 */
    public static List<String> explode(String sep, String s) {
        String t = s == null ? "" : s;
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int i = t.indexOf(sep, from);
            if (i < 0) {
                out.add(t.substring(from));
                return out;
            }
            out.add(t.substring(from, i));
            from = i + sep.length();
        }
    }

    /** {@code str_replace($search, $replace, $s)}（单个查找串；查找串为空时原样返回）。 */
    public static String replace(String s, String search, String rep) {
        return search.isEmpty() ? s : s.replace(search, rep);
    }

    /** {@code mac_format_text($s, $allowSpace)}（application/common.php:1234-1242）。 */
    public static String formatText(Object v, boolean allowSpace) {
        String s = str(v);
        // str_replace 按查找数组的顺序依次替换整个字符串
        for (String f : allowSpace ? List.of("/", "，", "|", "、", ",,", ",,,") : List.of("/", "，", "|", "、", ",,", ",,,", " ")) {
            s = s.replace(f, ",");
        }
        return s;
    }

    /**
     * {@code mac_txt_merge($txt, $str)}（application/common.php:1248-1266）：{@code str} 为空时原样返回 {@code txt}；
     * {@code class_filter} 不是 {@code '0'} 时 {@code str} 超过 2 个字符就先去掉"片"、再（仍超过 2 个字符时）去掉"剧"；两边各
     * {@code mac_format_text} 后按逗号拆开、合并、去重（保留第一次）、去掉空项（{@code array_filter}，含 {@code "0"}），以逗号连接。
     */
    public static Object txtMerge(Object txt, Object str, boolean classFilter, String slice, String drama) {
        if (empty(str)) {
            return txt;
        }
        String s = str(str);
        if (classFilter) {
            if (s.codePointCount(0, s.length()) > 2) {
                s = replace(s, slice, "");
            }
            if (s.codePointCount(0, s.length()) > 2) {
                s = replace(s, drama, "");
            }
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String x : explode(",", formatText(txt, false))) {
            seen.add(x);
        }
        for (String x : explode(",", formatText(s, false))) {
            seen.add(x);
        }
        List<String> out = new ArrayList<>();
        for (String x : seen) {
            if (!x.isEmpty() && !x.equals("0")) {
                out.add(x);
            }
        }
        return String.join(",", out);
    }

    /** {@code mac_txt_explain($txt, true)} 的结果：{@code from}、{@code to} 两列。 */
    public record Pairs(List<String> from, List<String> to) {
        public boolean isEmpty() {
            return from.isEmpty();
        }
    }

    /**
     * {@code mac_txt_explain($txt, true)}（application/common.php:915-934）：按 {@code #} 拆开，含 {@code =} 的项在第一个 {@code =}
     * 处分成 from 与 to；含 {@code &} 和 {@code ;} 的一边做 {@code html_entity_decode}（这里只解码 HTML 4 的五个基本实体与数字实体，其他命名
     * 实体原样保留，27 文档 D-B19-6）。
     */
    public static Pairs txtExplain(Object txt) {
        List<String> from = new ArrayList<>();
        List<String> to = new ArrayList<>();
        for (String v : explode("#", str(txt))) {
            int eq = v.indexOf('=');
            if (eq < 0) {
                continue;
            }
            from.add(entityDecode(v.substring(0, eq)));
            to.add(entityDecode(v.substring(eq + 1)));
        }
        return new Pairs(from, to);
    }

    private static String entityDecode(String s) {
        if (s.indexOf('&') < 0 || s.indexOf(';') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos|#039);").matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            String e = m.group(1);
            String r = switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#039" -> "'";
                default -> {
                    try {
                        int cp = e.charAt(1) == 'x' || e.charAt(1) == 'X' ? Integer.parseInt(e.substring(2), 16)
                                : Integer.parseInt(e.substring(1));
                        yield Character.isValidCodePoint(cp) && cp != 0 ? new String(Character.toChars(cp)) : m.group();
                    } catch (NumberFormatException overflow) {
                        yield m.group();
                    }
                }
            };
            sb.append(r);
            last = m.end();
        }
        return sb.append(s.substring(last)).toString();
    }

    /**
     * {@code mac_rep_pse_syn($pairs, $txt)}（application/common.php:936-943）：{@code txt} 为空时先变成空串；有替换表时按
     * {@code str_replace(from[], to[], txt)} 依次替换（空的查找串跳过）。
     */
    public static String repPseSyn(Pairs p, Object txt) {
        String s = empty(txt) ? "" : str(txt);
        if (p.isEmpty()) {
            return s;
        }
        for (int i = 0; i < p.from().size(); i++) {
            s = replace(s, p.from().get(i), p.to().get(i));
        }
        return s;
    }

    /**
     * {@code mac_rep_pse_rnd($words, $txt)}（application/common.php:876-913，{@code $id = 0}）：没有随机词时原样返回；正文为空时随机取一个词；
     * 否则以 {@code crc32(txt)} 选词，插在第一个 {@code <br>}（依次找 {@code <br/>}、{@code <br />}、{@code </p>}、"。"、"！"、"!"、"？"、"?"，
     * 位置为 0 也继续找）之前，没有时放在最前。
     */
    public static Object repPseRnd(List<String> words, Object txt, java.util.function.IntUnaryOperator rnd) {
        if (words.isEmpty()) {
            return txt;
        }
        int i = words.size();
        if (empty(txt)) {
            return words.get(rnd.applyAsInt(i));
        }
        String s = str(txt);
        CRC32 crc = new CRC32();
        crc.update(bytes(s));
        long id = crc.getValue();
        int k = s.codePointCount(0, s.length());
        int j = mbStrpos(s, "<br>");
        for (String needle : List.of("<br/>", "<br />", "</p>", "。", "！", "!", "？", "?")) {
            if (j <= 0) {
                j = mbStrpos(s, needle);
            }
        }
        String w = words.get((int) (id % i));
        if (j > 0) {
            return macSubstring(s, j - 1) + w + macSubstring(s, k - j, j);
        }
        return w + s;
    }

    /** {@code mb_strpos} 的字符位置；没有时 -1（旧代码里 {@code false == 0}，与 0 同样处理）。 */
    private static int mbStrpos(String s, String needle) {
        int i = s.indexOf(needle);
        return i < 0 ? -1 : s.codePointCount(0, i);
    }

    /**
     * {@code mac_array_filter($words, $s) !== false}（application/common.php:716-730）：去掉空项与 {@code "0"} 后没有过滤词时为假；否则
     * {@code str_replace($words, '*', $s) != $s}（宽松比较）。
     */
    public static boolean inFilter(List<String> words, Object s) {
        List<String> w = new ArrayList<>();
        for (String x : words) {
            if (!x.isEmpty() && !x.equals("0")) {
                w.add(x);
            }
        }
        if (w.isEmpty()) {
            return false;
        }
        String orig = str(s);
        String r = orig;
        for (String x : w) {
            r = r.replace(x, "*");
        }
        return !looseEquals(r, orig);
    }

    /** {@code mac_like_arr($s)}：按逗号拆开，每项两边加 {@code %}。 */
    public static List<String> likeArr(String s) {
        List<String> out = new ArrayList<>();
        for (String x : explode(",", s)) {
            out.add("%" + x + "%");
        }
        return out;
    }

    /** {@code strtoupper(substr($en, 0, 1))}。 */
    public static String letter(String en) {
        if (en.isEmpty()) {
            return "";
        }
        char c = en.charAt(0);
        return String.valueOf(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}

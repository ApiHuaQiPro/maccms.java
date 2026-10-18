package com.maccms.backend.common.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按旧 PHP 的方式读写 cookie（B08，docs/architecture/17-b08-member-session-captcha.md 第 2.1 节），纯计算。
 *
 * <p>读取（{@link #parse}）复刻 PHP 填充 {@code $_COOKIE} 的规则，由 {@code member/php80-golden.json}、{@code php73-golden.json}
 * 的 parse 用例锁定：只按 {@code ;} 分段（逗号不是分隔符）；名前的空白去掉，名中的空格、点和没有配对的 {@code [} 变成 {@code _}；
 * 名以 {@code [} 开头或为空时整段忽略；带 {@code [...]} 的名是数组（只标记为数组，不保留内容）；同名的标量<b>先到先得</b>，
 * 数组会覆盖先到的同名标量、标量不覆盖先到的数组；
 * 没有 {@code =} 的段值为空串；名不解码；值按 PHP 8.0 的 {@code rawurldecode} 解码（{@code %XX}，{@code +} 不变；PHP 7.3 会把 {@code +}
 * 解成空格，是两版之间唯一的解析差异）。值按字节解码后以 UTF-8 解释，非法字节序列在 {@link Value#bytes()} 中原样保留。
 *
 * <p>写出（{@link #setCookie}、{@link #deleteCookie}）复刻 PHP 8.0 {@code setcookie} 的头格式：值用 {@code rawurlencode}，
 * 有效期写 {@code expires=…; Max-Age=…}，{@code path} 小写；不加 HttpOnly/Secure/SameSite（与旧 {@code application/config.php}
 * 的 cookie 设置相同）。删除写成 {@code name=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=…}。
 */
public final class PhpCookies {

    private static final String DELETED_EXPIRES = "Thu, 01-Jan-1970 00:00:01 GMT";
    private static final String[] DAYS = {"Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
        "Nov", "Dec"};

    private PhpCookies() {
    }

    /** 一个 cookie 值。{@code array} 为 true 表示旧 PHP 中是数组（{@code name[...]=…}），此时 {@link #bytes()} 为空。 */
    public record Value(byte[] bytes, boolean array) {

        /** 按 UTF-8 解释的文本（非法序列替换为 U+FFFD；需要逐字节比较时用 {@link #bytes()}）。 */
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 解析请求中的全部 {@code Cookie} 头（多个头按 {@code ; } 连接，与只有一个头时相同）。
     *
     * @return 名 → 值，按首次出现的顺序；不可变
     */
    public static Map<String, Value> parse(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return parse(String.join("; ", headers));
    }

    /** 解析一个 {@code Cookie} 头的原始字节（ISO-8859-1 字符即字节）。 */
    public static Map<String, Value> parse(String header) {
        Map<String, Value> out = new LinkedHashMap<>();
        if (header == null) {
            return Map.of();
        }
        for (String segment : header.split(";", -1)) {
            int eq = segment.indexOf('=');
            String rawName = eq < 0 ? segment : segment.substring(0, eq);
            byte[] value = eq < 0 ? new byte[0] : rawUrlDecode(segment.substring(eq + 1));
            int start = 0;
            while (start < rawName.length() && isSpace(rawName.charAt(start))) {
                start++;
            }
            // 名不做 URL 解码（%61 仍是 %61）
            register(out, rawName.substring(start), value);
        }
        return Collections.unmodifiableMap(out);
    }

    /** PHP php_register_variable_ex 的名字规则（只区分标量与数组）。 */
    private static void register(Map<String, Value> out, String name, byte[] value) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == ' ') {
            i++;
        }
        name = name.substring(i);
        int bracket = name.indexOf('[');
        boolean array = false;
        String base = name;
        if (bracket >= 0) {
            if (name.indexOf(']', bracket) >= 0) {
                array = true;
                base = name.substring(0, bracket);
            } else {
                // 没有配对的 [：旧版把它当成普通字符并换成 _，其后的部分保留
                base = name.substring(0, bracket) + "_" + name.substring(bracket + 1);
            }
        }
        if (base.isEmpty()) {
            return;
        }
        String key = mangle(base);
        String utf8Key = new String(key.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        Value existing = out.get(utf8Key);
        if (array) {
            if (existing == null) {
                out.put(utf8Key, new Value(new byte[0], true));
            } else if (!existing.array()) {
                // 已有同名标量：PHP 用数组覆盖（标量的"先到先得"只在两者都是标量时成立）
                out.put(utf8Key, new Value(new byte[0], true));
            }
            return;
        }
        if (existing == null) {
            out.put(utf8Key, new Value(value, false));
        }
    }

    private static String mangle(String base) {
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            sb.append(c == ' ' || c == '.' ? '_' : c);
        }
        return sb.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == '\f';
    }

    /** PHP {@code rawurldecode}：{@code %XX}（大小写十六进制）解为一个字节，其余原样（{@code +} 不变，不合法的 % 序列保留）。 */
    public static byte[] rawUrlDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 2;
            } else {
                writeChar(out, c);
            }
        }
        return out.toByteArray();
    }

    /** PHP {@code urldecode}：同 {@link #rawUrlDecode}，另把 {@code +} 解成空格。输入输出都是字节。 */
    public static byte[] urlDecode(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            int c = in[i] & 0xFF;
            if (c == '+') {
                out.write(' ');
            } else if (c == '%' && i + 2 < in.length && hex((char) (in[i + 1] & 0xFF)) >= 0
                    && hex((char) (in[i + 2] & 0xFF)) >= 0) {
                out.write(hex((char) (in[i + 1] & 0xFF)) * 16 + hex((char) (in[i + 2] & 0xFF)));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    private static void writeChar(ByteArrayOutputStream out, char c) {
        if (c < 0x100) {
            out.write(c);
        } else {
            // 头部按 ISO-8859-1 读取时不会出现；防御性地按 UTF-8 写出
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** PHP {@code rawurlencode}（RFC 3986：只保留 {@code A-Z a-z 0-9 - . _ ~}），按 UTF-8 字节编码。 */
    public static String rawUrlEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    /**
     * PHP 8.0 {@code setcookie(name, value, expire, path)} 的 {@code Set-Cookie} 头值（不含 {@code Set-Cookie: }）。
     *
     * @param name          cookie 名（调用方保证不含 {@code =,; \t\r\n\013\014}）
     * @param value         原值；空串按 PHP 写成删除
     * @param expiresEpoch  到期时刻（秒），0 表示会话 cookie
     * @param nowEpoch      当前时刻（秒），用于 Max-Age
     * @param path          cookie 路径
     */
    public static String setCookie(String name, String value, long expiresEpoch, long nowEpoch, String path) {
        if (value.isEmpty()) {
            return deleteCookie(name, path);
        }
        StringBuilder sb = new StringBuilder(name).append('=').append(rawUrlEncode(value));
        if (expiresEpoch > 0) {
            sb.append("; expires=").append(httpDate(expiresEpoch)).append("; Max-Age=")
                    .append(Math.max(0, expiresEpoch - nowEpoch));
        }
        return sb.append("; path=").append(path).toString();
    }

    /** PHP {@code setcookie(name, '', 过去的时间, path)}：ThinkPHP {@code Cookie::delete} 的写法。 */
    public static String deleteCookie(String name, String path) {
        return name + "=deleted; expires=" + DELETED_EXPIRES + "; Max-Age=0; path=" + path;
    }

    /** PHP 的 {@code D, d-M-Y H:i:s T}（GMT）。 */
    static String httpDate(long epoch) {
        long days = Math.floorDiv(epoch, 86400L);
        long secs = Math.floorMod(epoch, 86400L);
        LocalDate d = LocalDate.ofEpochDay(days);
        return String.format(Locale.ROOT, "%s, %02d-%s-%04d %02d:%02d:%02d GMT",
                DAYS[(int) Math.floorMod(days, 7L)], d.getDayOfMonth(), MONTHS[d.getMonthValue() - 1], d.getYear(),
                secs / 3600, (secs / 60) % 60, secs % 60);
    }
}

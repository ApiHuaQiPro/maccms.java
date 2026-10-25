package com.maccms.backend.admin.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 后台入参的旧语义（B12，21 文档第 4.1 节），全部按<b>字节</b>处理：
 * <ul>
 * <li>{@link #filterExp}：ThinkPHP 5.0 {@code Request::filterExp}（thinkphp/library/think/Request.php:1122-1129）。{@code input()} 取到的
 * 每个值（含数组元素）整值匹配查询表达式关键字（不分大小写，PCRE 的 {@code $} 也匹配末尾换行之前）时末尾加一个空格——所以旧版密码
 * {@code like} 实际保存、比较的是 {@code md5("like ")}（黄金 {@code login-pwd-like-filterexp}）。</li>
 * <li>{@link #md5Hex}：管理员密码 {@code md5(原值)}，不 trim、不 urldecode、不 htmlspecialchars（03 草稿第 4.3 (7) 节）。</li>
 * <li>{@link #empty}：PHP {@code empty()} 对字符串：{@code ""} 与 {@code "0"}。</li>
 * </ul>
 */
public final class LegacyInput {

    private static final Set<String> EXPRESSIONS = Set.of("EXP", "NEQ", "GT", "EGT", "LT", "ELT", "OR", "XOR", "LIKE",
            "NOTLIKE", "NOT LIKE", "NOT BETWEEN", "NOTBETWEEN", "BETWEEN", "NOT EXISTS", "NOTEXISTS", "EXISTS", "NOT NULL",
            "NOTNULL", "NULL", "BETWEEN TIME", "NOT BETWEEN TIME", "NOTBETWEEN TIME", "NOTIN", "NOT IN", "IN");
    private static final Pattern NUMERIC = Pattern.compile("[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?"
            + "[ \\t\\n\\r\\x0B\\f]*");

    private LegacyInput() {
    }

    /** ThinkPHP {@code filterExp}：匹配时返回末尾加一个空格（0x20）的新数组，否则原样返回。 */
    public static byte[] filterExp(byte[] value) {
        if (value == null) {
            return null;
        }
        int end = value.length;
        if (end > 0 && value[end - 1] == '\n') {
            end--;
        }
        if (end == 0 || end > 16) {
            return value;
        }
        StringBuilder sb = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            int b = value[i] & 0xFF;
            if (b >= 0x80) {
                return value;
            }
            sb.append((char) b);
        }
        if (!EXPRESSIONS.contains(sb.toString().toUpperCase(Locale.ROOT))) {
            return value;
        }
        byte[] out = new byte[value.length + 1];
        System.arraycopy(value, 0, out, 0, value.length);
        out[value.length] = ' ';
        return out;
    }

    /** {@code md5($bytes)}，32 位小写十六进制。 */
    public static String md5Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PHP {@code empty()}：{@code null}、{@code ""}、{@code "0"}。 */
    public static boolean empty(byte[] value) {
        return value == null || value.length == 0 || (value.length == 1 && value[0] == '0');
    }

    /**
     * 严格按 UTF-8 解码；不是合法 UTF-8 或含 4 字节字符（mac_admin 的 utf8 列存不下、也比较不到）时为 null。
     */
    public static String utf8Bmp(byte[] value) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
            for (int i = 0; i < s.length(); i++) {
                if (Character.isSurrogate(s.charAt(i))) {
                    return null;
                }
            }
            return s;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * PHP 7.3/8.0 {@code htmlspecialchars($s)} 的默认行为（{@code ENT_COMPAT}，UTF-8）：只转 {@code & " < >}，非法 UTF-8 时返回空串。
     */
    public static String htmlspecialchars(byte[] value) {
        String s = utf8Any(value);
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * PHP 8 的 {@code $v == 0} / {@code $v == '0'}、{@code $v == '1'} 一类比较：{@code value} 是数字字符串（允许首尾空白）且数值等于
     * {@code target}。用于旧版 {@code in_array($val, ['0','1'])}（宽松比较）与 {@code admin_login_verify != '0'}。
     */
    public static boolean looseEqualsNumber(String value, int target) {
        if (value == null || !NUMERIC.matcher(value).matches()) {
            return false;
        }
        try {
            return Double.parseDouble(value.trim()) == target;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 不检查 4 字节字符的 UTF-8 解码；非法时为 null。 */
    static String utf8Any(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}

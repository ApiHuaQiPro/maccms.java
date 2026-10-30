package com.maccms.backend.admin.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B15 后台动作用到的几个 PHP 内置函数与旧公共函数的等价实现（24 文档第 4.2 节）。只做计算，不读文件。
 */
public final class LegacyPhp {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LegacyPhp() {
    }

    /** 按 UTF-8 严格解码；不是合法 UTF-8 时为 null。 */
    public static String utf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** PHP {@code trim($s)}：去掉首尾的空格、\t、\n、\r、\0、\x0B。 */
    public static String trim(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && trimmable(s.charAt(b))) {
            b++;
        }
        while (e > b && trimmable(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(b, e);
    }

    private static boolean trimmable(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

    /** 旧 {@code mac_replace_text($txt, 2)}（application/common.php:141-147）：去掉 \n，\r 换成 {@code #}。 */
    public static String replaceText2(String s) {
        return s.replace("\n", "").replace("\r", "#");
    }

    /** PHP {@code md5($s)}（小写十六进制）。 */
    public static String md5(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * PHP {@code json_decode($s, true)}：对象与数组都成为 PHP 数组（对象的键按 PHP 规则规范化，{@code "1"} 是整数键），整数超出 64 位时为浮点，
     * 不是合法 JSON 时为 null（PHP 的 null 与解码失败同样是 null，调用方都按"没有数据"处理）。
     */
    public static PhpValue jsonDecode(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        JsonNode n;
        try {
            n = JSON.readTree(s);
        } catch (JacksonException e) {
            return null;
        }
        return n == null || n.isMissingNode() ? null : node(n);
    }

    private static PhpValue node(JsonNode n) {
        if (n.isObject()) {
            Map<Object, PhpValue> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                m.put(PhpArray.normalizeKey(e.getKey()), node(e.getValue()));
            }
            return new PhpArray(m);
        }
        if (n.isArray()) {
            Map<Object, PhpValue> m = new LinkedHashMap<>();
            long i = 0;
            for (JsonNode x : n) {
                m.put(i++, node(x));
            }
            return new PhpArray(m);
        }
        if (n.isString()) {
            return new PhpValue.PhpString(n.asString());
        }
        if (n.isBoolean()) {
            return new PhpValue.PhpBool(n.asBoolean());
        }
        if (n.isNull()) {
            return PhpValue.PhpNull.INSTANCE;
        }
        if (n.isIntegralNumber()) {
            BigInteger v = n.bigIntegerValue();
            if (v.bitLength() < 64) {
                return new PhpValue.PhpInt(v.longValue());
            }
            return new PhpValue.PhpFloat(v.doubleValue());
        }
        return new PhpValue.PhpFloat(n.asDouble());
    }

    /**
     * {@code parse_ini_string($s, true)}（INI_SCANNER_NORMAL）的常用子集，只取第一个 {@code [节]} 之前的 {@code 键 = 值}（ThinkPHP
     * {@code Config::load} 读模板 {@code info.ini}、插件 {@code info.ini} 用到的就是这些）：键与值去掉首尾空白；值可用双引号或单引号包住；
     * 不带引号的 {@code true/on/yes} 为 {@code "1"}，{@code false/off/no/none/null} 为空串；{@code ;} 开头的行是注释；没有 {@code =} 的行忽略。
     * 键原样（区分大小写），后出现的同名键覆盖前面的。
     */
    public static LinkedHashMap<String, String> parseIni(String s) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String raw : s.split("\r\n|\r|\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[")) {
                break;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            } else {
                int semi = value.indexOf(';');
                if (semi >= 0) {
                    value = value.substring(0, semi).strip();
                }
                switch (value.toLowerCase(Locale.ROOT)) {
                    case "true", "on", "yes" -> value = "1";
                    case "false", "off", "no", "none", "null" -> value = "";
                    default -> {
                    }
                }
            }
            out.put(key, value);
        }
        return out;
    }

    /** {@code explode($sep, $s)}（{@code $sep} 非空）。 */
    public static List<String> explode(String sep, String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (true) {
            int i = s.indexOf(sep, start);
            if (i < 0) {
                out.add(s.substring(start));
                return out;
            }
            out.add(s.substring(start, i));
            start = i + sep.length();
        }
    }

    /** {@code join($sep, $array)}：各项按 PHP {@code (string)}。 */
    public static String join(String sep, PhpArray a) {
        List<String> parts = new ArrayList<>();
        for (PhpValue v : a.entries().values()) {
            parts.add(PhpValues.text(v));
        }
        return String.join(sep, parts);
    }

    /** 旧 {@code mac_get_body($text, $start, $end)}（application/common.php:1506-1523）：两个标记之间的文字；文本为空或找不到时为空串。 */
    public static String getBody(String text, String start, String end) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int s = text.indexOf(start);
        if (s <= 0) {
            // 旧版 strpos(...) != ""：位置 0 在 PHP 7 被当作没找到（8 里能找到）；本文件里标记从不出现在开头
            return "";
        }
        String rest = text.substring(s + start.length());
        int e = rest.indexOf(end);
        return e < 0 ? "" : rest.substring(0, e);
    }
}

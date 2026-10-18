package com.maccms.backend.site.application;

import com.maccms.backend.content.application.PhpIntval;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旧 {@code mac_param_url()}（application/common.php:1560-1606）对 ajax 接口参数的读取（B06，16 文档第 4.8–4.10 节）：
 * 路径参数（ThinkPHP PATH_INFO 的 {@code /键/值} 对）被查询串/POST 覆盖（{@code array_merge(input(), $_REQUEST)}）；整数键按
 * {@code intval}；文本键为 {@code htmlspecialchars(urldecode(trim()))}（PHP 7.3/8.0 的默认 {@code ENT_COMPAT}：转换 {@code & < > "}，
 * 不转换单引号）。urldecode 后的字节不是合法 UTF-8 时为空（调用方按参数不合法处理）。
 */
public final class LegacyParams {

    /** ThinkPHP 5.0 PATH_INFO 参数（Route::parseUrlParams 的 {@code (\w+)\|([^\|]+)}）。 */
    private static final Pattern PATH_PAIR = Pattern.compile("(\\w+)\\|([^|]+)");

    private final Map<String, String> raw;

    private LegacyParams(Map<String, String> raw) {
        this.raw = raw;
    }

    /**
     * @param pathRest 操作名之后的路径段（已按 PATH_INFO 解码一次）
     * @param request  查询串与 POST 参数（名 → 最后一个值）
     */
    public static LegacyParams of(List<String> pathRest, Map<String, String> request) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!pathRest.isEmpty()) {
            Matcher pm = PATH_PAIR.matcher(String.join("|", pathRest));
            while (pm.find()) {
                m.put(pm.group(1), pm.group(2));
            }
        }
        m.putAll(request);
        return new LegacyParams(m);
    }

    /**
     * ThinkPHP {@code input()}（Request::param，thinkphp/library/think/Request.php:658）：GET &lt; POST &lt; 路径参数（二维码接口用它，
     * index/controller/Qrcode.php:16）。
     */
    public static LegacyParams thinkInput(List<String> pathRest, Map<String, String> request) {
        Map<String, String> m = new LinkedHashMap<>(request);
        if (!pathRest.isEmpty()) {
            Matcher pm = PATH_PAIR.matcher(String.join("|", pathRest));
            while (pm.find()) {
                m.put(pm.group(1), pm.group(2));
            }
        }
        return new LegacyParams(m);
    }

    /** {@code intval($input[key])}。 */
    public long intval(String key) {
        return PhpIntval.of(raw.get(key));
    }

    /** {@code htmlspecialchars(urldecode(trim($input[key])))}；参数缺失时为空串，解码后不是合法 UTF-8 时为空。 */
    public Optional<String> text(String key) {
        String v = raw.get(key);
        if (v == null) {
            return Optional.of("");
        }
        return urldecode(phpTrim(v)).map(LegacyParams::htmlspecialchars);
    }

    /** 原始值（ThinkPHP {@code input()}，不再处理）；缺失时为 null。 */
    public String raw(String key) {
        return raw.get(key);
    }

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

    private static boolean isTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

    /** PHP {@code urldecode}：{@code +} 为空格，完整的 {@code %HH} 解码，不完整的原样保留。 */
    static Optional<String> urldecode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return Optional.of(s);
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '+') {
                out.write(' ');
            } else if (in[i] == '%' && i + 2 < in.length && Character.digit(in[i + 1], 16) >= 0
                    && Character.digit(in[i + 2], 16) >= 0) {
                out.write(Character.digit(in[i + 1], 16) * 16 + Character.digit(in[i + 2], 16));
                i += 2;
            } else {
                out.write(in[i]);
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** PHP 7.3/8.0 {@code htmlspecialchars($s)}（ENT_COMPAT | ENT_HTML401，double_encode）。 */
    static String htmlspecialchars(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

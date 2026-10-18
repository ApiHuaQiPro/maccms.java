package com.maccms.backend.member;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B08 的 PHP 黄金样本（{@code backend/src/test/php/member_golden.php} 生成）：{@code member/php80-golden.json}（PHP 8.0.30）、
 * {@code member/php73-golden.json}（PHP 7.3.33）。字符串一律是十六进制字节。
 */
public final class MemberGolden {

    public static final String PHP80 = "member/php80-golden.json";
    public static final String PHP73 = "member/php73-golden.json";

    private MemberGolden() {
    }

    public static JsonNode load(String resource) {
        try (InputStream in = MemberGolden.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] bytes(JsonNode hex) {
        return HexFormat.of().parseHex(hex.asString());
    }

    /** 十六进制字节按 ISO-8859-1 转成字符（即 HTTP 头里的原始字节）。 */
    public static String latin1(JsonNode hex) {
        return new String(bytes(hex), StandardCharsets.ISO_8859_1);
    }

    public static String utf8(JsonNode hex) {
        return new String(bytes(hex), StandardCharsets.UTF_8);
    }

    /**
     * PHP {@code $_COOKIE} 的黄金表示 → 名 → 值（标量为 UTF-8 解释后的字节，数组为 {@link #ARRAY}）。
     */
    public static Map<String, String> cookies(JsonNode seen) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode e : seen.get("array")) {
            JsonNode v = e.get("v");
            out.put(utf8(e.get("k")), v.isObject() ? ARRAY : new String(bytes(v), StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    /** 数组值的占位。 */
    public static final String ARRAY = "<array>";
}

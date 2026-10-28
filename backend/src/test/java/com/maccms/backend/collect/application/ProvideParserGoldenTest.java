package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/**
 * Provide 文件解析的 PHP 黄金对照（B18，26 文档第 7 节）：{@code src/test/php/collect_provide_golden.php} 在 PHP 8.0 与 7.3 上直接调用旧
 * {@code Collect.php} 的解析方法（不联网、不连库），记录每个用例的输入、绑定、过滤词、返回值与每行的入库预检结果。新版对同一输入的
 * {@link ProvideParser#parse} + {@link ProvideParser#bind} 结果逐值相同（键的类型与顺序、整数与浮点的区别都算），{@link ProvideParser#precheck}
 * 逐行相同。失败的用例比较 {@code code} 与语言包键（旧版的 {@code msg} 里还拼了请求地址，新版是本地文件，不比较这部分）；
 * {@code page.url} 同理不比较。
 */
class ProvideParserGoldenTest {

    private static JsonNode golden(String v) {
        try (InputStream in = ProvideParserGoldenTest.class.getClassLoader()
                .getResourceAsStream("collect/provide/php" + v + "-provide-golden.json")) {
            JsonFactory f = JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10_000).build()).build();
            return JsonMapper.builder(f).build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"80", "73"})
    void everyCaseMatchesPhp(String v) {
        JsonNode g = golden(v);
        String cjflag = g.get("cjflag").asString();
        assertThat(g.get("cases").size()).isGreaterThanOrEqualTo(55);
        for (JsonNode c : g.get("cases")) {
            String name = c.get("name").asString();
            ProvideKind kind = ProvideKind.valueOf(c.get("fn").asString().toUpperCase(java.util.Locale.ROOT));
            byte[] input = Base64.getDecoder().decode(c.get("input").asString());
            ProvideDocument doc = ProvideParser.parse(kind, c.get("type").asString(), c.get("ac").asString(), c.get("param").asString(), input);
            @SuppressWarnings("unchecked")
            Map<Object, Object> expected = (Map<Object, Object>) typed(c.get("result"));
            PhpArray bind = phpArray(c.get("bind"));
            Object url = expected.get("page") instanceof Map<?, ?> p ? p.get("url") : null;
            Map<Object, Object> actual = ProvideParser.bind(doc, cjflag, bind, url == null ? "" : (String) url, k -> k);
            if (((Long) expected.get("code")) != 1L) {
                assertThat(actual.get("code")).as(name).isEqualTo(expected.get("code"));
                String key = ((String) expected.get("msg")).split("[,:]", 2)[0];
                assertThat((String) actual.get("msg")).as(name).startsWith(key);
                assertThat(c.get("checks").size()).as(name).isZero();
                continue;
            }
            assertThat(dump(actual)).as(name).isEqualTo(dump(expected));
            // 缓存的写法：编码再解码后不变
            assertThat(dump(cacheRoundTrip(actual))).as(name + " 缓存往返").isEqualTo(dump(expected));
            List<String> checks = new ArrayList<>();
            ((Map<?, ?>) actual.get("data")).values().forEach(row -> checks.add(ProvideParser.precheck(kind, row, c.get("filter").asString())));
            List<String> want = new ArrayList<>();
            c.get("checks").forEach(x -> want.add(x.asString()));
            assertThat(checks).as(name + " 预检").isEqualTo(want);
        }
    }

    private static Object cacheRoundTrip(Object v) {
        try {
            return ProvideJson.decode(ProvideJson.encode(v).getBytes(StandardCharsets.UTF_8));
        } catch (ProvideJson.MalformedJsonException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ 带类型的 PHP 值

    static Object typed(JsonNode t) {
        String type = t.get(0).asString();
        return switch (type) {
            case "s" -> new String(Base64.getDecoder().decode(t.get(1).asString()), StandardCharsets.UTF_8);
            case "i" -> Long.parseLong(t.get(1).asString());
            case "f" -> ByteBuffer.wrap(HexFormat.of().parseHex(t.get(1).asString())).getDouble();
            case "b" -> t.get(1).asBoolean();
            case "n" -> null;
            case "a" -> {
                Map<Object, Object> m = new LinkedHashMap<>();
                for (JsonNode e : t.get(1)) {
                    Object k = e.get(0).asString().equals("i") ? (Object) Long.parseLong(e.get(1).asString())
                            : new String(Base64.getDecoder().decode(e.get(1).asString()), StandardCharsets.UTF_8);
                    m.put(k, typed(e.get(2)));
                }
                yield m;
            }
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static PhpArray phpArray(JsonNode t) {
        Map<Object, PhpValue> m = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<Object, Object> raw = (Map<Object, Object>) typed(t);
        raw.forEach((k, x) -> m.put(k, x instanceof Long l ? new PhpValue.PhpInt(l) : new PhpValue.PhpString(String.valueOf(x))));
        return new PhpArray(m);
    }

    /** 带类型、保持顺序的写法（{@code Map.equals} 不比较顺序）。 */
    static String dump(Object v) {
        StringBuilder sb = new StringBuilder();
        dump(sb, v);
        return sb.toString();
    }

    private static void dump(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("n");
            case String s -> sb.append("s\"").append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            case Long l -> sb.append("i").append(l);
            case Double d -> sb.append("f").append(Long.toHexString(Double.doubleToRawLongBits(d)));
            case Boolean b -> sb.append("b").append(b);
            case Map<?, ?> m -> {
                sb.append('{');
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    dump(sb, e.getKey());
                    sb.append(':');
                    dump(sb, e.getValue());
                    sb.append(',');
                }
                sb.append('}');
            }
            default -> throw new IllegalArgumentException(v.getClass().getName());
        }
    }
}

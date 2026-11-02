package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V-11 值语义黄金（技术设计 C-17、§6.5 RT-2～RT-5）：{@code semantics/php73-vectors.json} 由 S3-05 的
 * {@code src/test/php/tpl_runtime_golden.php --semantics} 在 PHP 7.3.33（{@code precision=14}）下生成，本测试逐条比对。
 *
 * <p>黄金只记录 PHP 的结果；部分分组（{@code rt2_array_keys}、{@code rt2_array_order}、{@code rt3_stringify}、
 * {@code rt4_compare}）没有写出输入值，这里的输入表逐字照抄生成器同名分组的源码（S3-05 {@code b71b1aa}），
 * 并先断言标签/顺序/输入编码与黄金一致，期望值一律只取自黄金。</p>
 *
 * <p>本类不覆盖的分组见 {@link #NOT_COVERED_HERE}：属于 S3-07 解释器或后续函数工作，测试会在黄金新增分组时报错。</p>
 */
class PhpSemanticsGoldenTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HexFormat HEX = HexFormat.of();

    /** 本类逐条比对的分组。 */
    private static final Set<String> COVERED = Set.of(
            "rt2_array_keys", "rt2_array_order", "rt3_stringify", "rt4_compare", "rt4_in_array",
            "rt5_undefined", "rt5_str_index", "rt5_str_index_bytes");

    /** 黄金里属于后续子任务的分组 → 归属（实现说明“剩余覆盖”一节同表）。 */
    static final Map<String, String> NOT_COVERED_HERE = Map.ofEntries(
            Map.entry("rt3_json", "json_encode：模板函数 C-27/S3-11（TemplateFunctions，V-15），非 C-17"),
            Map.entry("gap1_bare_identifier", "裸标识符求值：S3-07 解释器（C-18/C-19），GAP-1 设计处理后实现"),
            Map.entry("gap1_mac_day_color", "mac_day：TemplateFunctionsGoldenTest（C-27/S3-11），依赖 GAP-1"),
            Map.entry("fn_mac_day", "mac_day：TemplateFunctionsGoldenTest（C-27/S3-11）"),
            Map.entry("fn_date", "date：TemplateFunctionsGoldenTest（C-27/S3-11）"),
            Map.entry("fn_list", "白名单函数返回值：TemplateFunctionsGoldenTest（C-27/S3-11）"),
            Map.entry("fn_url_list", "mac_url_* 签名：TemplateFunctionsGoldenTest（C-27/S3-11）"),
            Map.entry("fn_internal", "内部函数签名：TemplateFunctionsGoldenTest（C-27/S3-11）"),
            Map.entry("tag_names", "标签名清单：TagLibCompileTest（C-22/S3-09）"),
            Map.entry("art_info_empty_where", "Art.infoData 1001 分支：TagModelsInfoDataTest（S3-12b）"),
            Map.entry("art_info_not_found", "Art.infoData 1002 分支：TagModelsInfoDataTest（S3-12b）"));

    private static JsonNode golden;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = PhpSemanticsGoldenTest.class.getResourceAsStream("/phptemplate/semantics/php73-vectors.json")) {
            assertThat(in).as("黄金 semantics/php73-vectors.json").isNotNull();
            golden = JSON.readTree(in);
        }
        JsonNode meta = golden.get("_meta");
        assertThat(meta.get("php").asString()).isEqualTo("7.3.33");
        assertThat(meta.get("int_size").asInt()).isEqualTo(8);
        assertThat(meta.get("precision").asString()).isEqualTo(String.valueOf(PhpSemantics.PRECISION));
    }

    @Test
    void everyGoldenGroupIsCoveredHereOrAssignedToALaterTask() {
        Set<String> groups = new TreeSet<>();
        golden.properties().forEach(e -> groups.add(e.getKey()));
        groups.remove("_meta");
        Set<String> expected = new TreeSet<>(COVERED);
        expected.addAll(NOT_COVERED_HERE.keySet());
        assertThat(groups).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ RT-2

    @Test
    void rt2ArrayKeysNormalizeLikePhp73() {
        // 生成器源码：$arr['1']='a'; $arr['01']='b'; $arr[1.7]='c'; $arr[true]='d'; $arr[null]='e'; …
        ZArray arr = new ZArray();
        arr.put(str("1"), str("a"));
        arr.put(str("01"), str("b"));
        arr.put(ZValue.of(1.7), str("c"));
        arr.put(ZValue.TRUE, str("d"));
        arr.put(ZValue.NULL, str("e"));
        arr.put(str("-3"), str("f"));
        arr.put(str(" 3"), str("g"));
        arr.put(str("3 "), str("h"));
        arr.put(str("+4"), str("i"));
        arr.put(str("-0"), str("j"));
        arr.put(str("1.0"), str("k"));
        arr.put(str("9223372036854775808"), str("l"));

        JsonNode g = golden.get("rt2_array_keys");
        assertThat(arr.size()).isEqualTo(g.size()).isEqualTo(10);
        int i = 0;
        for (Map.Entry<Object, ZValue> e : arr.entries().entrySet()) {
            JsonNode v = g.get(i++);
            ZValue key = ZArray.keyValue(e.getKey());
            assertThat(utf8(e.getValue())).as("值").isEqualTo(v.get("written").asString());
            assertThat(key.typeName()).as("键类型 " + v).isEqualTo(v.get("key_type").asString());
            assertThat(utf8(key)).as("键 " + v).isEqualTo(v.get("key").asString());
        }
    }

    @Test
    void rt2ArrayOrderAndAutoIndexMatchPhp73() {
        // 生成器源码：$o['b']=1; $o[]=2; $o['a']=3; $o[10]=4; $o[]=5;
        ZArray o = new ZArray();
        o.put("b", ZValue.of(1L));
        o.append(ZValue.of(2L));
        o.put("a", ZValue.of(3L));
        o.put(10, ZValue.of(4L));
        o.append(ZValue.of(5L));

        JsonNode g = golden.get("rt2_array_order");
        assertThat(o.size()).isEqualTo(g.size()).isEqualTo(5);
        int i = 0;
        for (Map.Entry<Object, ZValue> e : o.entries().entrySet()) {
            JsonNode v = g.get(i++);
            ZValue key = ZArray.keyValue(e.getKey());
            assertThat(key.typeName()).isEqualTo(v.get("key_type").asString());
            assertThat(utf8(key)).isEqualTo(v.get("key").asString());
            assertThat(((ZValue.Int) e.getValue()).value()).isEqualTo(v.get("value").asLong());
        }
    }

    // ------------------------------------------------------------------ RT-3

    @Test
    void rt3StringifyMatchesPhp73Precision14() {
        // 生成器源码的输入表（标签 → 值），顺序同黄金
        Map<String, ZValue> in = new LinkedHashMap<>();
        in.put("true", ZValue.TRUE);
        in.put("false", ZValue.FALSE);
        in.put("null", ZValue.NULL);
        in.put("int0", ZValue.of(0L));
        in.put("int-42", ZValue.of(-42L));
        in.put("int_max", ZValue.of(Long.MAX_VALUE));
        in.put("int_min", ZValue.of(Long.MIN_VALUE));
        in.put("float0", ZValue.of(0.0));
        in.put("negzero", ZValue.of(-0.0));
        in.put("float1", ZValue.of(1.0));
        in.put("float1_5", ZValue.of(1.5));
        in.put("float_1e14", ZValue.of(1.0e14));
        in.put("float_1e15", ZValue.of(1.0e15));
        in.put("float_1e-5", ZValue.of(1.0e-5));
        in.put("float_third", ZValue.of(1.0 / 3));
        in.put("float_pi", ZValue.of(Math.PI));
        in.put("float_1_0000000000001", ZValue.of(1.0000000000001));
        in.put("float_big", ZValue.of(123456789012345678.0));
        in.put("empty_str", str(""));
        in.put("str_zero", str("0"));
        in.put("str_num", str("0123"));
        in.put("array", ZArray.list(ZValue.of(1L), ZValue.of(2L)));

        JsonNode g = golden.get("rt3_stringify");
        List<String> labels = new ArrayList<>();
        g.forEach(v -> labels.add(v.get("label").asString()));
        assertThat(labels).as("输入表与黄金标签一致").containsExactlyElementsOf(in.keySet());
        for (JsonNode v : g) {
            ZValue value = in.get(v.get("label").asString());
            assertThat(value.typeName()).as(v.get("label").asString()).isEqualTo(v.get("type").asString());
            assertThat(utf8(PhpSemantics.toStr(value))).as(v.get("label").asString()).isEqualTo(v.get("string").asString());
        }
    }

    // ------------------------------------------------------------------ RT-4

    @Test
    void rt4LooseComparisonMatchesPhp73() {
        // 生成器源码的 $pairs（标签、左、右），顺序同黄金
        List<Object[]> pairs = List.of(
                pair("\"abc\" == 0", str("abc"), ZValue.of(0L)),
                pair("\"1\" == 1", str("1"), ZValue.of(1L)),
                pair("\"01\" == 1", str("01"), ZValue.of(1L)),
                pair("\"1 \" == 1", str("1 "), ZValue.of(1L)),
                pair("\" 1\" == 1", str(" 1"), ZValue.of(1L)),
                pair("\"1x\" == 1", str("1x"), ZValue.of(1L)),
                pair("\"x1\" == 1", str("x1"), ZValue.of(1L)),
                pair("\"\" == 0", str(""), ZValue.of(0L)),
                pair("null == 0", ZValue.NULL, ZValue.of(0L)),
                pair("null == \"\"", ZValue.NULL, str("")),
                pair("null == false", ZValue.NULL, ZValue.FALSE),
                pair("\"0\" == false", str("0"), ZValue.FALSE),
                pair("\"0.0\" == 0", str("0.0"), ZValue.of(0L)),
                pair("\"1e2\" == 100", str("1e2"), ZValue.of(100L)),
                pair("[] == false", new ZArray(), ZValue.FALSE),
                pair("[] == 0", new ZArray(), ZValue.of(0L)),
                pair("[0] == false", ZArray.list(ZValue.of(0L)), ZValue.FALSE),
                pair("\"abc\" == \"ABC\"", str("abc"), str("ABC")),
                pair("\"10\" == \"1e1\"", str("10"), str("1e1")),
                pair("PHP_INT_MAX == \"9223372036854775807\"", ZValue.of(Long.MAX_VALUE), str("9223372036854775807")),
                pair("\"9223372036854775808\" == 9.2233720368548E+18", str("9223372036854775808"),
                        ZValue.of(9.2233720368547758E+18)));

        JsonNode g = golden.get("rt4_compare");
        assertThat(g.size()).isEqualTo(pairs.size()).isEqualTo(21);
        for (int i = 0; i < pairs.size(); i++) {
            JsonNode v = g.get(i);
            String label = (String) pairs.get(i)[0];
            ZValue a = (ZValue) pairs.get(i)[1];
            ZValue b = (ZValue) pairs.get(i)[2];
            assertThat(v.get("label").asString()).isEqualTo(label);
            assertEncodedAs(a, v.get("lhs"), label);
            assertEncodedAs(b, v.get("rhs"), label);
            assertThat(PhpSemantics.looseEquals(a, b)).as(label + " ==").isEqualTo(v.get("eq").asBoolean());
            assertThat(!PhpSemantics.looseEquals(a, b)).as(label + " !=").isEqualTo(v.get("neq").asBoolean());
            assertThat(PhpSemantics.identical(a, b)).as(label + " ===").isEqualTo(v.get("identical").asBoolean());
            assertThat(PhpSemantics.isSmaller(a, b)).as(label + " <").isEqualTo(v.get("lt").asBoolean());
            assertThat(PhpSemantics.isSmaller(b, a)).as(label + " >").isEqualTo(v.get("gt").asBoolean());
            assertThat(PhpSemantics.isSmallerOrEqual(a, b)).as(label + " <=").isEqualTo(v.get("lte").asBoolean());
            assertThat(PhpSemantics.isSmallerOrEqual(b, a)).as(label + " >=").isEqualTo(v.get("gte").asBoolean());
        }
    }

    @Test
    void rt4InArrayIsLooseByDefault() {
        // 生成器源码：[['abc',[0,1]], ['1',[1,2]], [0,['abc']], ['abc',['ABC']], [null,[0]], [null,['']]]
        List<Object[]> cases = List.of(
                new Object[] {str("abc"), ZArray.list(ZValue.of(0L), ZValue.of(1L))},
                new Object[] {str("1"), ZArray.list(ZValue.of(1L), ZValue.of(2L))},
                new Object[] {ZValue.of(0L), ZArray.list(str("abc"))},
                new Object[] {str("abc"), ZArray.list(str("ABC"))},
                new Object[] {ZValue.NULL, ZArray.list(ZValue.of(0L))},
                new Object[] {ZValue.NULL, ZArray.list(str(""))});

        JsonNode g = golden.get("rt4_in_array");
        assertThat(g.size()).isEqualTo(cases.size()).isEqualTo(6);
        for (int i = 0; i < cases.size(); i++) {
            JsonNode v = g.get(i);
            ZValue needle = (ZValue) cases.get(i)[0];
            ZArray haystack = (ZArray) cases.get(i)[1];
            assertEncodedAs(needle, v.get("needle"), "needle#" + i);
            assertThat(haystack.size()).isEqualTo(v.get("haystack").size());
            for (int k = 0; k < haystack.size(); k++) {
                assertEncodedAs(haystack.get(k), v.get("haystack").get(k), "haystack#" + i);
            }
            assertThat(PhpSemantics.inArray(needle, haystack, false)).as("in_array#" + i).isEqualTo(v.get("in_array").asBoolean());
            assertThat(PhpSemantics.inArray(needle, haystack, true)).as("strict#" + i)
                    .isEqualTo(v.get("in_array_strict").asBoolean());
        }
    }

    // ------------------------------------------------------------------ RT-5

    @Test
    void rt5UndefinedValuesReadAsNull() {
        Map<String, JsonNode> g = byLabel(golden.get("rt5_undefined"));
        assertThat(g.keySet()).containsExactly(
                "undefined var", "undefined index", "nested undefined", "isset undefined", "empty undefined");
        ZArray undef = new ZArray();

        // 未定义变量：变量表查找属于 S3-07 解释器；值层面，取不到的值就是 PHP null
        ZValue undefinedVar = ZValue.NULL;
        assertThat(utf8(PhpSemantics.toStr(undefinedVar))).isEqualTo(g.get("undefined var").get("string").asString());
        assertThat(undefinedVar instanceof ZValue.Null).isEqualTo(g.get("undefined var").get("is_null").asBoolean());

        ZValue undefinedIndex = PhpSemantics.fetchDim(undef, str("nope"));
        assertThat(utf8(PhpSemantics.toStr(undefinedIndex))).isEqualTo(g.get("undefined index").get("string").asString());
        assertThat(undefinedIndex instanceof ZValue.Null).isEqualTo(g.get("undefined index").get("is_null").asBoolean());

        ZValue nested = PhpSemantics.fetchDim(PhpSemantics.fetchDim(PhpSemantics.fetchDim(undef, str("a")), str("b")), str("c"));
        assertThat(utf8(PhpSemantics.toStr(nested))).isEqualTo(g.get("nested undefined").get("string").asString());

        assertThat(PhpSemantics.isset(PhpSemantics.fetchDimQuiet(undef, str("nope"))))
                .isEqualTo(g.get("isset undefined").get("isset").asBoolean());
        assertThat(PhpSemantics.empty(PhpSemantics.fetchDimQuiet(undef, str("nope"))))
                .isEqualTo(g.get("empty undefined").get("empty").asBoolean());
    }

    @Test
    void rt5StringOffsetsReadSingleBytes() {
        // 生成器源码：$s = 'abc'; foreach ([0, 1, 2, 3, -1, '1'] as $i) … @$s[$i]
        ZValue s = str("abc");
        List<ZValue> dims = List.of(ZValue.of(0L), ZValue.of(1L), ZValue.of(2L), ZValue.of(3L), ZValue.of(-1L), str("1"));
        JsonNode g = golden.get("rt5_str_index");
        assertThat(g.size()).isEqualTo(dims.size());
        for (int i = 0; i < dims.size(); i++) {
            JsonNode v = g.get(i);
            ZValue dim = dims.get(i);
            assertThat(dim.typeName()).isEqualTo(v.get("index").isNumber() ? "integer" : "string");
            assertThat(utf8(PhpSemantics.toStr(dim))).isEqualTo(v.get("index").asString());
            ZValue got = PhpSemantics.fetchDim(s, dim);
            assertThat(got.typeName()).as("type #" + i).isEqualTo(v.get("type").asString());
            assertThat(utf8(got)).as("char #" + i).isEqualTo(v.get("char").asString());
        }

        // "你" 的 UTF-8 三个字节：下标按字节，不按字符
        ZValue u = ZValue.bytes(new byte[] {(byte) 0xe4, (byte) 0xbd, (byte) 0xa0});
        JsonNode gb = golden.get("rt5_str_index_bytes");
        assertThat(gb.size()).isEqualTo(4);
        for (JsonNode v : gb) {
            ZValue got = PhpSemantics.fetchDim(u, ZValue.of(v.get("index").asLong()));
            assertThat(HEX.formatHex(PhpSemantics.toBytes(got))).as("byte #" + v.get("index")).isEqualTo(v.get("byte_hex").asString());
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 生成器 {@code hexOf}：标量为 {@code gettype} + {@code (string)} 的字节，数组为 {@code serializeArray} 的字节。 */
    private static void assertEncodedAs(ZValue v, JsonNode enc, String label) {
        assertThat(v.typeName()).as(label + " 输入类型").isEqualTo(enc.get("type").asString());
        byte[] bytes = v instanceof ZArray a ? serializeArray(a).getBytes(StandardCharsets.UTF_8) : PhpSemantics.toBytes(v);
        assertThat(HEX.formatHex(bytes)).as(label + " 输入编码").isEqualTo(enc.get("hex").asString());
    }

    /** 生成器 {@code serializeArray}。 */
    private static String serializeArray(ZArray a) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Object, ZValue> e : a.entries().entrySet()) {
            sb.append(e.getKey() instanceof Long ? "i:" : "s:").append(utf8(ZArray.keyValue(e.getKey()))).append("=>");
            if (e.getValue() instanceof ZArray inner) {
                sb.append('[').append(serializeArray(inner)).append(']');
            } else {
                sb.append(e.getValue().typeName()).append('(').append(utf8(PhpSemantics.toStr(e.getValue()))).append(')');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private static Map<String, JsonNode> byLabel(JsonNode arr) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        arr.forEach(v -> m.put(v.get("label").asString(), v));
        return m;
    }

    private static Object[] pair(String label, ZValue a, ZValue b) {
        return new Object[] {label, a, b};
    }

    private static ZValue.Str str(String s) {
        return ZValue.of(s);
    }

    /** PHP {@code (string)$v} 再按 UTF-8 解码，用于与黄金里的 JSON 字符串比较。 */
    private static String utf8(ZValue v) {
        return PhpSemantics.toStr(v).utf8();
    }
}

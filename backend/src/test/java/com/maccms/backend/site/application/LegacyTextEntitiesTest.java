package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 普通文本字符引用黄金向量（src/test/resources/site/rich-text/entity-vectors.json）。每条向量断言：
 * <ol>
 * <li>{@code decodeOnce(input) == expect}；</li>
 * <li>compat：expect 与 PHP {@code html_entity_decode}（php_decode，PHP 脚本生成）、浏览器（jsoup
 * {@code Parser.unescapeEntities(input, false)}，独立于被测实现）的关系与向量记录一致；</li>
 * <li>往返：{@code decodeOnce(php_filter_xss) == php_plain}——经旧 mac_filter_xss 存库的值解码一次恰好还原录入文本，
 * 双重编码、字面 {@code &lt;} 都不会被多解一层。</li>
 * </ol>
 */
class LegacyTextEntitiesTest {

    private static final String RESOURCE = "/site/rich-text/entity-vectors.json";
    private static final Set<String> COMPAT = Set.of("SAME", "PHP_ONLY", "BROWSER_ONLY", "NEITHER");

    private static JsonNode data;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LegacyTextEntitiesTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            data = JsonMapper.builder().build().readTree(in);
        }
    }

    @TestFactory
    Stream<DynamicTest> vectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : data.get("vectors")) {
            tests.add(DynamicTest.dynamicTest(v.get("id").stringValue(), () -> check(v)));
        }
        return tests.stream();
    }

    private static void check(JsonNode v) {
        String input = v.get("input").stringValue();
        String expect = v.get("expect").stringValue();
        String compat = v.get("compat").stringValue();
        String php = v.get("php_decode").stringValue();
        String browser = spec(Parser.unescapeEntities(input, false));

        assertThat(LegacyTextEntities.decodeOnce(input)).isEqualTo(expect);
        assertThat(COMPAT).contains(compat);
        assertThat(expect.equals(php)).as("与 PHP html_entity_decode 一致").isEqualTo(compat.equals("SAME")
                || compat.equals("PHP_ONLY"));
        assertThat(expect.equals(browser)).as("与浏览器解码一致").isEqualTo(compat.equals("SAME")
                || compat.equals("BROWSER_ONLY"));
        if (!compat.equals("SAME")) {
            assertThat(v.has("note") || compat.equals("BROWSER_ONLY")).as("差异需要说明").isTrue();
        }
        assertThat(LegacyTextEntities.decodeOnce(v.get("php_filter_xss").stringValue()))
                .as("mac_filter_xss 存库值解码一次还原录入文本")
                .isEqualTo(v.get("php_plain").stringValue());
    }

    /**
     * jsoup 1.23.2 把 {@code &#0;} 解码为 U+0000、把代理区引用解码为单个代理项；HTML 规范（numeric character reference
     * end state）规定两者都是 U+FFFD。参照值按规范修正这两处，其余取 jsoup 结果。
     */
    private static String spec(String jsoup) {
        StringBuilder sb = new StringBuilder();
        jsoup.codePoints().forEach(cp -> sb.appendCodePoint(cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF) ? 0xFFFD : cp));
        return sb.toString();
    }

    @Test
    void vectorsCoverEveryCompatKind() {
        Set<String> kinds = new TreeSet<>();
        data.get("vectors").forEach(v -> kinds.add(v.get("compat").stringValue()));
        assertThat(kinds).isEqualTo(new TreeSet<>(COMPAT));
    }

    @Test
    void decodesOnlyOnceAndKeepsUnrelatedText() {
        assertThat(LegacyTextEntities.decodeOnce(null)).isEmpty();
        String noAmp = "没有引用 <b> \"x\" 'y'";
        assertThat(LegacyTextEntities.decodeOnce(noAmp)).isSameAs(noAmp);
        // 只解一层：结果再解一次才会变
        String once = LegacyTextEntities.decodeOnce("&amp;amp;lt;");
        assertThat(once).isEqualTo("&amp;lt;");
        assertThat(LegacyTextEntities.decodeOnce(once)).isEqualTo("&lt;");
        // 名称过长、未知名称、名称后不是分号
        assertThat(LegacyTextEntities.decodeOnce("&" + "a".repeat(40) + ";")).isEqualTo("&" + "a".repeat(40) + ";");
        assertThat(LegacyTextEntities.decodeOnce("&CounterClockwiseContourIntegral;")).isEqualTo("∳");
        assertThat(LegacyTextEntities.decodeOnce("&amp-&lt;")).isEqualTo("&amp-<");
    }

    @Test
    void outputHasNoControlCharsFromReferences() {
        for (int cp = 0; cp <= 0xA0; cp++) {
            String decoded = LegacyTextEntities.decodeOnce("&#" + cp + ";");
            int c = decoded.codePointAt(0);
            assertThat(Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\f' && c != '\r')
                    .as("&#%d;", cp).isFalse();
        }
    }
}

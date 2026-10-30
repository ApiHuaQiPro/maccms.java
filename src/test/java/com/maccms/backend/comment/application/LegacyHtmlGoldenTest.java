package com.maccms.backend.comment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评论/留言写库前的文本处理与旧 PHP 逐字节相同（B11，20 文档第 4.2 节）：黄金向量由 {@code src/test/php/interaction_text_golden.php}
 * 在 PHP 8.0.30 与 7.3.33 上生成（两版结果相同），覆盖 htmlentities 的实体表、mac_filter_words 的各种过滤词、mac_filter_xss 的再转义与 trim、
 * 非法 UTF-8、NUL、4 字节字符，以及 mac_restore_htmlfilter。
 */
class LegacyHtmlGoldenTest {

    private static final HexFormat HEX = HexFormat.of();

    @ParameterizedTest
    @ValueSource(strings = {"interaction/text-php80-golden.json", "interaction/text-php73-golden.json"})
    void writePipelineMatchesPhp(String resource) {
        JsonNode golden = load(resource);
        int n = 0;
        for (JsonNode c : golden.get("text")) {
            byte[] input = HEX.parseHex(c.get("input").asString());
            List<byte[]> words = new ArrayList<>();
            for (String w : c.get("words").asString().split(",", -1)) {
                words.add(w.getBytes(StandardCharsets.UTF_8));
            }
            byte[] filtered = LegacyHtml.filterWords(input, words);
            String what = resource + " words=" + c.get("words").asString() + " input=" + c.get("input").asString();
            assertThat(HEX.formatHex(filtered)).as(what + " filter_words").isEqualTo(c.get("filtered").asString());
            String entities = LegacyHtml.htmlentities(filtered);
            assertThat(HEX.formatHex(entities.getBytes(StandardCharsets.UTF_8))).as(what + " htmlentities")
                    .isEqualTo(c.get("entities").asString());
            assertThat(HEX.formatHex(LegacyHtml.filterXss(entities).getBytes(StandardCharsets.UTF_8)))
                    .as(what + " mac_filter_xss").isEqualTo(c.get("stored").asString());
            n++;
        }
        assertThat(n).isEqualTo(192);
        for (JsonNode c : golden.get("restore")) {
            String in = new String(HEX.parseHex(c.get("input").asString()), StandardCharsets.UTF_8);
            assertThat(LegacyHtml.restoreHtmlFilter(in)).as(resource + " restore " + in)
                    .isEqualTo(new String(HEX.parseHex(c.get("output").asString()), StandardCharsets.UTF_8));
        }
    }

    @Test
    void entityTableHasTheHtml401Entries() {
        assertThat(LegacyHtml.entityCount()).isEqualTo(252);
        assertThat(LegacyHtml.htmlentities("'".getBytes(StandardCharsets.UTF_8))).isEqualTo("'");
        assertThat(LegacyHtml.htmlentities("好€".getBytes(StandardCharsets.UTF_8))).isEqualTo("好&euro;");
    }

    @Test
    void displayTextIsWhatTheBrowserShowedForTheOldRawOutput() {
        // 库里的值：htmlentities 之后再 mac_filter_xss（两次转义）
        assertThat(LegacyHtml.displayText("&amp;lt;b&amp;gt;粗&amp;lt;/b&amp;gt; &amp;amp; &amp;quot;引&amp;quot; &#039;单&#039;"))
                .isEqualTo("<b>粗</b> & \"引\" '单'");
        assertThat(LegacyHtml.displayText("&amp;eacute;")).isEqualTo("é");
        assertThat(LegacyHtml.displayText("好看")).isEqualTo("好看");
        // 没有 &amp; 时 mac_restore_htmlfilter 不动，浏览器仍解码一次
        assertThat(LegacyHtml.displayText("&#039;x&#039;")).isEqualTo("'x'");
        assertThat(LegacyHtml.displayText("a &unknown; b &#xZZ; &")).isEqualTo("a &unknown; b &#xZZ; &");
    }

    private static JsonNode load(String resource) {
        try (InputStream in = LegacyHtmlGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

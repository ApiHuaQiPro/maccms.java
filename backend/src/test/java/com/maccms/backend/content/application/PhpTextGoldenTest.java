package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 后台内容保存的旧 PHP 文本函数与 PHP 逐字节相同（B13，22 文档第 4 节）：黄金向量由 {@code backend/src/test/php/content_text_golden.php}
 * 在 PHP 8.0.30 与 7.3.33 上生成（手写用例 + 1500 个固定种子的随机串）。两版只有 {@code mac_substring} 不同（PHP 8 把字母换成空格），
 * 新版按 PHP 7.3（D-B13-5），PHP 8 的那一项另行断言差异。
 */
class PhpTextGoldenTest {

    private static final HexFormat HEX = HexFormat.of();

    @ParameterizedTest
    @ValueSource(strings = {"content/admin/text-php80-golden.json", "content/admin/text-php73-golden.json"})
    void textFunctionsMatchPhp(String resource) {
        JsonNode golden = load(resource);
        boolean php8 = golden.get("php").asString().startsWith("8.");
        int n = 0;
        int blurbDiffs = 0;
        for (JsonNode c : golden.get("cases")) {
            byte[] in = HEX.parseHex(c.get("input").asString());
            String what = resource + " input=" + c.get("input").asString();
            assertThat(hex(PhpText.stripTags(in))).as(what + " strip_tags").isEqualTo(c.get("strip_tags").asString());
            assertThat(hex(PhpText.htmlspecialchars(in))).as(what + " htmlspecialchars")
                    .isEqualTo(c.get("htmlspecialchars").asString());
            assertThat(hex(PhpText.filterXss(in))).as(what + " filter_xss").isEqualTo(c.get("filter_xss").asString());
            for (int len : new int[] {10, 100, 255}) {
                assertThat(hex(PhpText.mbSubstr(in, len))).as(what + " mb_substr " + len)
                        .isEqualTo(c.get("mb_substr_" + len).asString());
            }
            byte[] blurb = PhpText.macSubstring(PhpText.stripTags(in), 100);
            byte[] blurbArt = PhpText.macSubstring(PhpText.strReplace(PhpText.stripTags(in),
                    "$$$".getBytes(StandardCharsets.US_ASCII), new byte[0]), 100);
            if (php8 && !hex(blurb).equals(c.get("blurb").asString())) {
                // D-B13-5：PHP 8 下 ord 64-127 的字符变成空格；把新版结果里的这些字节换成空格后应与 PHP 8 相同
                assertThat(hex(php8Substring(blurb))).as(what + " blurb(php8)").isEqualTo(c.get("blurb").asString());
                assertThat(hex(php8Substring(blurbArt))).as(what + " blurb_art(php8)").isEqualTo(c.get("blurb_art").asString());
                blurbDiffs++;
            } else {
                assertThat(hex(blurb)).as(what + " blurb").isEqualTo(c.get("blurb").asString());
                assertThat(hex(blurbArt)).as(what + " blurb_art").isEqualTo(c.get("blurb_art").asString());
            }
            if (PhpText.validUtf8(in)) {
                String py = LegacyPinyin.get(new String(in, StandardCharsets.UTF_8));
                assertThat(hex(py.getBytes(StandardCharsets.UTF_8))).as(what + " pinyin").isEqualTo(c.get("pinyin").asString());
                assertThat(hex(LegacyPinyin.letter(py.getBytes(StandardCharsets.UTF_8)))).as(what + " letter")
                        .isEqualTo(c.get("letter").asString());
            }
            assertThat(hex(PhpText.contentImageProtocol(in, "http"))).as(what + " img http")
                    .isEqualTo(c.get("content_img_http").asString());
            assertThat(hex(PhpText.contentImageProtocol(in, "https"))).as(what + " img https")
                    .isEqualTo(c.get("content_img_https").asString());
            assertThat(hex(PhpText.contentImageProtocol(in, ""))).as(what + " img empty")
                    .isEqualTo(c.get("content_img_empty").asString());
            assertThat(hex(PhpText.restoreHtmlFilter(in))).as(what + " restore").isEqualTo(c.get("restore").asString());
            assertThat(hex(PhpText.urldecode(in))).as(what + " urldecode").isEqualTo(c.get("urldecode").asString());
            n++;
        }
        assertThat(n).isGreaterThan(1600);
        if (php8) {
            assertThat(blurbDiffs).as("PHP 8 的 mac_substring 差异确实出现").isGreaterThan(100);
        }
    }

    @Test
    void pinyinOfCommonTitles() {
        assertThat(LegacyPinyin.get("复仇者联盟4：终局之战")).isEqualTo("fuchouzhelianmeng4zhongjuzhizhan");
        assertThat(LegacyPinyin.get(" Iron Man 3 ")).isEqualTo("IronMan3");
        assertThat(LegacyPinyin.letter("fu".getBytes(StandardCharsets.US_ASCII))).isEqualTo("F".getBytes(StandardCharsets.US_ASCII));
    }

    /** PHP 8 的 mac_substring 结果：单字节且 ord 在 64-127 的字符换成空格（其余不变）。 */
    private static byte[] php8Substring(byte[] php73) {
        byte[] out = php73.clone();
        for (int i = 0; i < out.length; i++) {
            int b = out[i] & 0xff;
            if (b >= 64 && b < 128) {
                out[i] = ' ';
            }
        }
        return out;
    }

    private static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    private static JsonNode load(String resource) {
        try (InputStream in = PhpTextGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

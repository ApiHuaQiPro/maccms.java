package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.site.application.RichTextSanitizer.Finding;
import com.maccms.backend.site.application.RichTextSanitizer.Outcome;
import com.maccms.backend.site.application.RichTextSanitizer.Result;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 富文本过滤的边界、稳定性与随机输入测试；逐条向量见 {@link RichTextSanitizerGoldenTest}。 */
@ExtendWith(OutputCaptureExtension.class)
class RichTextSanitizerTest {

    /** 不启用 img_key：img_api 前缀不是幂等的（见 RichTextSanitizerGoldenTest），这里要检查再过滤一次不变。 */
    private static final RichTextSanitizer SANITIZER = RichTextSanitizer.create(ImageUrlNormalizer.create(
            new ImageUrlConfig("https", "local", "", "", ""),
            ImageUrlGoldenVectorsTest.site("https://www.maccms.test/")));

    @Test
    void emptyInputs() {
        for (String s : new String[] {null, "", " ", "\t\r\n", "　"}) {
            Result r = SANITIZER.sanitize(s);
            assertThat(r.outcome()).as(String.valueOf(s)).isEqualTo(Outcome.EMPTY);
            assertThat(r.html()).isSameAs(SafeHtml.EMPTY);
            assertThat(r.html().isEmpty()).isTrue();
            assertThat(r.findings()).isEmpty();
        }
    }

    @Test
    void tooLongInputIsRejectedWholeNotTruncated() {
        String unit = "<p>正文&amp;</p>";
        String atLimit = unit.repeat(RichTextSanitizer.MAX_INPUT_LENGTH / unit.length())
                + "x".repeat(RichTextSanitizer.MAX_INPUT_LENGTH % unit.length());
        assertThat(atLimit).hasSize(RichTextSanitizer.MAX_INPUT_LENGTH);
        Result ok = SANITIZER.sanitize(atLimit);
        assertThat(ok.outcome()).isEqualTo(Outcome.SANITIZED);
        assertThat(ok.html().html()).startsWith("<p>正文&amp;</p>").endsWith("x");

        Result tooLong = SANITIZER.sanitize(atLimit + "y");
        assertThat(tooLong.outcome()).isEqualTo(Outcome.TOO_LONG);
        assertThat(tooLong.html()).isSameAs(SafeHtml.EMPTY);
    }

    @Test
    void deepNestingDoesNotOverflowAndStaysSafe() {
        String deep = "<div>".repeat(9_000) + "<script>alert(1)</script>深<img src=x onerror=1>"
                + "</div>".repeat(9_000);
        Result r = SANITIZER.sanitize(deep);
        assertThat(r.outcome()).isEqualTo(Outcome.SANITIZED);
        assertThat(r.html().html()).contains("深").doesNotContain("script").doesNotContain("onerror");
        RichTextSanitizerGoldenTest.assertSafe(r.html().html());
        RichTextSanitizerGoldenTest.assertStable(SANITIZER, r.html().html());
    }

    /**
     * 大量兄弟元素被删除/去标签时不能是平方级：曾经逐个 unwrap/remove，20 000 个未闭合 {@code <a>x} 要 1.7 秒、
     * 47 500 个要 12 秒；改为标记后由 Cleaner 一次复制，47 500 个约 0.1 秒。每种写法都接近输入上限，上限 2 秒留出机器差异。
     */
    @Test
    void manySiblingRejectionsStayFast() {
        List<String> inputs = List.of(
                "<a>".repeat(33_000),
                "<img src=javascript:x>".repeat(4_500),
                "<script></script>".repeat(5_800),
                "<a href=//evil.test>y</a>".repeat(3_900),
                "<font color=red>z".repeat(5_800));
        for (String input : inputs) {
            assertThat(input.length()).isLessThanOrEqualTo(RichTextSanitizer.MAX_INPUT_LENGTH);
            org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(2), () -> {
                Result r = SANITIZER.sanitize(input);
                assertThat(r.outcome()).isEqualTo(Outcome.SANITIZED);
                assertThat(r.html().html()).doesNotContain("<a").doesNotContain("<img").doesNotContain("<script")
                        .doesNotContain("<font");
            });
        }
    }

    /** 格式元素重建会放大输出（{@code <p><i>x} 约 5 倍）；超过输出上限时整段不输出。 */
    @Test
    void outputLimitStopsAmplification() {
        String input = "<p><i>x".repeat(14_142);
        assertThat(input.length()).isLessThanOrEqualTo(RichTextSanitizer.MAX_INPUT_LENGTH);
        Result r = SANITIZER.sanitize(input);
        assertThat(r.outcome()).isEqualTo(Outcome.TOO_LONG);
        assertThat(r.html()).isSameAs(SafeHtml.EMPTY);
        // 同样的写法规模小时正常输出
        assertThat(SANITIZER.sanitize("<p><i>x".repeat(100)).outcome()).isEqualTo(Outcome.SANITIZED);
    }

    /** 表格嵌套超过 jsoup 的 512 层深度上限时，重新解析不收敛，按防御性分支整段不输出。 */
    @Test
    void absurdTableNestingFailsClosed() {
        Result r = SANITIZER.sanitize("<table><td>".repeat(2_000) + "正文");
        assertThat(r.outcome()).isEqualTo(Outcome.UNSTABLE);
        assertThat(r.html()).isSameAs(SafeHtml.EMPTY);
        assertThat(SANITIZER.sanitize("<table><td>".repeat(20) + "正文").outcome()).isEqualTo(Outcome.SANITIZED);
    }

    @Test
    void unpairedSurrogatesBecomeReplacementCharacter() {
        Result r = SANITIZER.sanitize("a\uD800b<img src=\"/a.jpg\" alt=\"x\uDC00y\">😀");
        assertThat(r.html().html()).isEqualTo("a�b<img src=\"/a.jpg\" alt=\"x�y\">😀");
        // 输出能按严格 UTF-8 编码（不可编码时抛异常，而不是静默替换成 ?）
        assertThat(strictUtf8(r.html().html())).isTrue();
    }

    /** 随机拼接危险片段：任何输入都不抛异常，输出满足安全不变量且稳定。种子固定，失败可复现。 */
    @Test
    void randomInputsNeverThrowAndStaySafe() {
        List<String> parts = List.of("<", ">", "/", "\"", "'", "=", "&", ";", "#", "x", " ", "\t", "\n", "\u0000",
                "\uD800", "中", "&amp;", "&lt;", "&#106;", "&#x0A;", "&copy", "javascript:", "java\tscript:", "//",
                "/\\", "data:", "mac:", "<script>", "</script>", "<svg>", "<math>", "<style>", "<!--", "-->",
                "<![CDATA[", "]]>", "<noscript>", "</noscript>", "<textarea>", "<template>", "<iframe>", "<a href=",
                "<img src=", " onerror=", " onclick=", " style=", " srcset=", "<p>", "</p>", "<table>", "<td>",
                "<b>", "<i>", "</b>", "<a>", "</a>", "<form>", "<select>", "<option>", "<title>", "<xmp>",
                "<pre>", "\n\n", "&#x0A;&#x0A;", "</table>", "<tr>", "<caption>", "<colgroup>", "<col>", "<li>", "<font>",
                "<plaintext>", "<object>", "<base href=", "<meta ", "<link ", "<body onload=", "<html>", "<br/>",
                "https://www.maccms.test/", "/upload/a.jpg", "upload/b.jpg", "width=", "99999", "colspan=");
        Random random = new Random(20261011L);
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + random.nextInt(40);
            for (int j = 0; j < n; j++) {
                sb.append(parts.get(random.nextInt(parts.size())));
            }
            String input = sb.toString();
            Result r = SANITIZER.sanitize(input);
            String html = r.html().html();
            assertThat(r.outcome()).as("第 %d 个随机输入：%s", i, visible(input)).isNotEqualTo(Outcome.UNSTABLE);
            try {
                RichTextSanitizerGoldenTest.assertSafe(html);
                RichTextSanitizerGoldenTest.assertStable(SANITIZER, html);
            } catch (AssertionError e) {
                throw new AssertionError("第 " + i + " 个随机输入：" + visible(input) + "，输出：" + visible(html)
                        + "，" + e.getMessage(), e);
            }
            assertThat(strictUtf8(html)).isTrue();
        }
    }

    @Test
    void concurrentUseGivesSameResult() {
        String input = "<p onclick=x>并发 <a href=\"https://www.maccms.test/\">外链</a><img src=\"mac://i.test/a.jpg\"></p>";
        String expected = SANITIZER.sanitize(input).html().html();
        Set<String> results = IntStream.range(0, 200).parallel()
                .mapToObj(i -> SANITIZER.sanitize(input).html().html())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(results).containsExactly(expected);
    }

    /** 过滤器不写日志；结果的 toString 也不含内容（避免被拼进日志）。 */
    @Test
    void neverLogsOrExposesContent(CapturedOutput output) {
        String marker = "SYNTHETIC-RICHTEXT-MARKER-4f1c";
        String input = "<script>" + marker + "</script><img src=\"javascript:" + marker + "\"><a href=\"//" + marker
                + "\">" + marker + "</a><p title=\"" + marker + "\">" + marker + "</p>";
        Result r = SANITIZER.sanitize(input);
        assertThat(r.findings()).contains(Finding.DANGEROUS_ELEMENT_REMOVED, Finding.IMAGE_REJECTED,
                Finding.LINK_REJECTED, Finding.ATTRIBUTE_REMOVED);
        assertThat(r.html().html()).contains(marker);
        assertThat(r.html().toString()).doesNotContain(marker);
        assertThat(r.toString()).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    /** 稳定化：按浏览器规则调整结构；遇到白名单外内容时不输出（正常流程到不了这里，单独测防御性分支）。 */
    @Test
    void stabilizeNormalizesStructureAndFailsClosed() {
        Set<Finding> findings = java.util.EnumSet.noneOf(Finding.class);
        assertThat(RichTextSanitizer.stabilize("<table><i>x</i></table>", findings)).contains("<i>x</i><table></table>");
        assertThat(findings).containsExactly(Finding.STRUCTURE_NORMALIZED);

        for (String bad : List.of("<script>x</script>", "<p onclick=\"x\">t</p>", "<a href=\"javascript:x\">t</a>",
                "<img src=\"//evil.test/a.png\">", "<!-- c -->", "<font>t</font>", "<p style=\"x\">t</p>")) {
            assertThat(RichTextSanitizer.stabilize(bad, java.util.EnumSet.noneOf(Finding.class))).as(bad).isEmpty();
        }
        assertThat(Outcome.valueOf("UNSTABLE")).isNotNull();
    }

    @Test
    void onlyTheSanitizerCanCreateSafeHtml() {
        assertThat(SafeHtml.class.getDeclaredConstructors())
                .allSatisfy(c -> assertThat(Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers()))
                        .isFalse());
        assertThat(Modifier.isFinal(SafeHtml.class.getModifiers())).isTrue();
        assertThatThrownBy(() -> RichTextSanitizer.create(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void imageSourcesGoThroughNormalizerExactlyOnce() {
        // 属性值经 jsoup 解码一次；交给规范化器前恢复 &amp;，规范化器再解码一次，总共一次
        assertThat(RichTextSanitizer.asLegacyAttributeText("/a?x=1&y=2&amp;z")).isEqualTo("/a?x=1&amp;y=2&amp;amp;z");
        Result r = SANITIZER.sanitize("<img src=\"/a.jpg?x=1&amp;amp;y=2&lt;\">");
        assertThat(r.findings()).containsExactly(Finding.IMAGE_REJECTED);
    }

    @Test
    void pomPinsJsoupAsTheOnlyHtmlSanitizer() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        assertThat(pom).containsPattern("(?s)<groupId>org\\.jsoup</groupId>\\s*<artifactId>jsoup</artifactId>"
                + "\\s*<version>1\\.23\\.2</version>\\s*</dependency>");
        assertThat(pom).doesNotContain("owasp-java-html-sanitizer").doesNotContain("antisamy");
    }

    private static String visible(String s) {
        StringBuilder sb = new StringBuilder();
        s.chars().forEach(c -> sb.append(c < 0x20 || (c >= 0x7f && c < 0xa0) || Character.isSurrogate((char) c)
                ? String.format("\\u%04X", c) : String.valueOf((char) c)));
        return sb.toString();
    }

    private static boolean strictUtf8(String s) {
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(s));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}

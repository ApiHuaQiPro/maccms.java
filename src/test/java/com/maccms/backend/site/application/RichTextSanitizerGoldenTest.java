package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.site.application.RichTextSanitizer.Finding;
import com.maccms.backend.site.application.RichTextSanitizer.Outcome;
import com.maccms.backend.site.application.RichTextSanitizer.Result;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 富文本过滤黄金向量（src/test/resources/site/rich-text/rich-text-vectors.json）。每条向量断言：
 * <ol>
 * <li>输出 HTML 与 expect 逐字相等，findings 相等；</li>
 * <li>{@link #assertSafe 安全不变量}：浏览器规则（jsoup）重新解析后只有白名单元素与属性、没有事件属性/style/注释/脚本数据，
 * href/src 都能通过 {@link SafeUrl}，没有控制字符；</li>
 * <li>稳定：再过滤一次输出不变、不再有任何改动；重新解析再序列化也不变（不会被浏览器解析成别的结构）。</li>
 * </ol>
 */
class RichTextSanitizerGoldenTest {

    private static final String RESOURCE = "/site/rich-text/rich-text-vectors.json";

    private static JsonNode data;
    static RichTextSanitizer sanitizer;
    /**
     * 稳定性检查用：同一配置去掉 img_key。img_api 前缀与旧 mac_url_img 一样不是幂等的
     * （/img.php?url=https://cdn… 仍命中 img_key，会再拼一次），所以"再过滤一次不变"只在不启用 img_key 时成立。
     */
    static RichTextSanitizer stableSanitizer;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = RichTextSanitizerGoldenTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            data = JsonMapper.builder().build().readTree(in);
        }
        JsonNode c = data.get("meta").get("image_config");
        sanitizer = vectorSanitizer(c, c.get("img_key").stringValue());
        stableSanitizer = vectorSanitizer(c, "");
    }

    private static RichTextSanitizer vectorSanitizer(JsonNode c, String imgKey) {
        SiteSettings site = ImageUrlGoldenVectorsTest.site(c.get("base_url").stringValue());
        return RichTextSanitizer.create(ImageUrlNormalizer.create(new ImageUrlConfig(c.get("protocol").stringValue(),
                c.get("mode").stringValue(), c.get("remoteurl").stringValue(), imgKey,
                c.get("img_api").stringValue()), site));
    }

    @Test
    void imgApiPrefixIsNotIdempotentLikeLegacy() {
        String once = sanitizer.sanitize("<img src=\"https://cdn.maccms.test/a.jpg\">").html().html();
        assertThat(once).isEqualTo("<img src=\"/img.php?url=https://cdn.maccms.test/a.jpg\">");
        assertThat(sanitizer.sanitize(once).html().html())
                .isEqualTo("<img src=\"/img.php?url=/img.php?url=https://cdn.maccms.test/a.jpg\">");
    }

    @TestFactory
    Stream<DynamicTest> vectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : data.get("vectors")) {
            tests.add(DynamicTest.dynamicTest(v.get("id").stringValue(), () -> check(v)));
        }
        return tests.stream();
    }

    @Test
    void vectorsCoverEveryRequiredCategoryAndFinding() {
        Set<String> categories = new TreeSet<>();
        EnumSet<Finding> findings = EnumSet.noneOf(Finding.class);
        Set<String> ids = new TreeSet<>();
        for (JsonNode v : data.get("vectors")) {
            String id = v.get("id").stringValue();
            assertThat(ids.add(id)).as("重复 id " + id).isTrue();
            categories.add(id.substring(0, id.indexOf('/')));
            v.get("findings").forEach(f -> findings.add(Finding.valueOf(f.stringValue())));
        }
        // 恶意标签、事件属性、URL 协议混淆、图片、CSS/嵌入、属性转义、实体边界、结构
        assertThat(categories).containsExactlyInAnyOrder("tag", "event", "url", "img", "css", "attr", "entity", "struct");
        assertThat(findings).containsExactlyInAnyOrder(Finding.values());
    }

    private static void check(JsonNode v) {
        String input = v.get("input").stringValue();
        Result r = sanitizer.sanitize(input);
        assertThat(r.outcome()).isEqualTo(Outcome.SANITIZED);
        assertThat(r.html().html()).isEqualTo(v.get("expect").stringValue());
        EnumSet<Finding> expected = EnumSet.noneOf(Finding.class);
        v.get("findings").forEach(f -> expected.add(Finding.valueOf(f.stringValue())));
        assertThat(r.findings()).as("findings").isEqualTo(expected);
        assertSafe(r.html().html());
        assertStable(stableSanitizer, r.html().html());
    }

    /** 按浏览器规则重新解析输出，检查只剩白名单内容。 */
    static void assertSafe(String html) {
        Document doc = Parser.parseBodyFragment(html, "");
        Element body = doc.body();
        List<Node> stack = new ArrayList<>(body.childNodes());
        while (!stack.isEmpty()) {
            Node node = stack.remove(stack.size() - 1);
            assertThat(node).as("注释或脚本数据").isNotInstanceOf(Comment.class).isNotInstanceOf(DataNode.class);
            if (node instanceof TextNode t) {
                assertNoControlChars(t.getWholeText());
                continue;
            }
            assertThat(node).isInstanceOf(Element.class);
            Element el = (Element) node;
            assertThat(RichTextSanitizer.SAFELIST.isSafeTag(el.normalName())).as("元素 " + el.normalName()).isTrue();
            assertThat(RichTextSanitizer.DROPPED_WITH_CONTENT).doesNotContain(el.normalName());
            for (Attribute attr : el.attributes()) {
                String name = attr.getKey();
                assertThat(name).doesNotStartWith("on").isNotEqualTo("style");
                assertThat(RichTextSanitizer.SAFELIST.isSafeAttribute(el.normalName(), el, attr))
                        .as(el.normalName() + "@" + name).isTrue();
                assertNoControlChars(attr.getValue());
                if (name.equals("href") || name.equals("src")) {
                    assertThat(SafeUrl.parse(attr.getValue())).as(name).isPresent();
                }
            }
            if (el.normalName().equals("a")) {
                assertThat(el.hasAttr("href")).as("链接必须有 href").isTrue();
                String rel = el.attr("rel");
                assertThat(rel).isEqualTo(SafeUrl.parse(el.attr("href")).orElseThrow().isAbsolute()
                        ? RichTextSanitizer.EXTERNAL_REL : "");
            }
            if (el.normalName().equals("img")) {
                assertThat(el.hasAttr("src")).as("图片必须有 src").isTrue();
            }
            stack.addAll(el.childNodes());
        }
    }

    /** 再过滤一次不变，且没有改动；按浏览器规则重新解析再序列化也不变。 */
    static void assertStable(RichTextSanitizer s, String html) {
        Result again = s.sanitize(html);
        assertThat(again.html().html()).as("再过滤一次").isEqualTo(html);
        assertThat(again.findings()).as("再过滤一次的改动").isEmpty();
        Document reparsed = Parser.parseBodyFragment(html, "");
        reparsed.outputSettings().prettyPrint(false).escapeMode(org.jsoup.nodes.Entities.EscapeMode.base)
                .charset("UTF-8");
        // HTML 序列化规范：pre 的第一个文字以换行开头时多写一个换行（解析时会忽略开始标签后的第一个换行）
        for (Element pre : reparsed.body().getElementsByTag("pre")) {
            if (pre.childNodeSize() > 0 && pre.childNode(0) instanceof TextNode t && t.getWholeText().startsWith("\n")) {
                t.text("\n" + t.getWholeText());
            }
        }
        assertThat(reparsed.body().html()).as("重新解析再序列化").isEqualTo(html);
    }

    private static void assertNoControlChars(String s) {
        s.chars().forEach(c -> assertThat(Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\f'
                && c != '\r').as("控制字符 U+%04X", c).isFalse());
        // codePoints() 把成对的代理项合成一个码点，剩下的代理区码点就是不成对的
        assertThat(s.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).as("不成对的代理项").isTrue();
    }
}

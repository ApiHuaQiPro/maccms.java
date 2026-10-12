package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.site.application.ImageUrlConfig;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.RichTextSanitizer;
import com.maccms.backend.site.application.SafeHtml;
import com.maccms.backend.site.application.SiteProperties;
import com.maccms.backend.site.application.SiteSettings;
import gg.jte.ContentType;
import gg.jte.Content;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

/**
 * 富文本过滤结果与 jte 模板的配合（MACC-12）：用与正式模板相同的严格站点策略编译测试模板（src/test/jte-richtext/），
 * 把 {@link SafeHtml} 包成 {@link Content} 放在元素内容位置渲染。页面适配类随详情页任务实现，这里只验证约定本身。
 */
class SiteRichTextTemplateTest {

    private static final RichTextSanitizer SANITIZER = RichTextSanitizer.create(ImageUrlNormalizer.create(
            new ImageUrlConfig("https", "local", "", "", ""),
            SiteSettings.from(new SiteProperties("true", "合成站", "https://www.maccms.test/", null, null, null, null,
                    null, null, null, null))));

    private static TemplateEngine engine(Path dir, String classDir) {
        TemplateEngine engine = TemplateEngine.create(new DirectoryCodeResolver(dir), Path.of("target", classDir),
                ContentType.Html);
        engine.setHtmlPolicy(new SiteTemplatePolicyTest.SiteHtmlPolicy());
        engine.setClassPath(Stream.of(gg.jte.html.HtmlTemplateOutput.class, Content.class)
                .map(SiteRichTextTemplateTest::location).distinct().toList());
        return engine;
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 详情页适配方式：只在元素内容位置原样写出过滤结果。 */
    private static Content content(SafeHtml html) {
        return output -> output.writeContent(html.html());
    }

    @Test
    void sanitizedHtmlRendersCompletePageInElementContext() {
        String raw = "<p onclick=\"x\">简介 &amp; 剧情 <b>重点</b></p><script>alert(1)</script>"
                + "<img src=\"mac://img.maccms.test/a.jpg\" onerror=\"alert(1)\" alt=\"海报\">"
                + "<a href=\"javascript:alert(1)\">坏链接</a><a href=\"https://www.maccms.test/v/2.html\">外链</a>";
        SafeHtml safe = SANITIZER.sanitize(raw).html();
        String title = "流浪地球 <2> & \"特别篇\"";

        StringOutput out = new StringOutput();
        engine(Path.of("src", "test", "jte-richtext"), "jte-richtext")
                .render("page/detail.jte", Map.of("title", title, "body", content(safe)), out);
        String page = out.toString();

        Document doc = Parser.htmlParser().parseInput(page, "");
        assertThat(doc.select("footer").text()).as("整页输出").isEqualTo("页尾");
        assertThat(doc.title()).isEqualTo(title);
        assertThat(doc.select("h1").text()).isEqualTo(title);
        Element rich = doc.selectFirst("div.rich-text");
        assertThat(rich).isNotNull();
        rich.ownerDocument().outputSettings().prettyPrint(false).escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
        assertThat(rich.html()).as("过滤结果原样嵌入，不被二次转义").isEqualTo(safe.html());
        assertThat(rich.text()).isEqualTo("简介 & 剧情 重点 坏链接外链");
        assertThat(rich.select("img").attr("src")).isEqualTo("https://img.maccms.test/a.jpg");
        assertThat(doc.select("script, [onclick], [onerror], a[href^=javascript]")).isEmpty();
        assertThat(page).doesNotContain("&amp;amp;");
    }

    /**
     * 误用：{@link Content} 放在属性里时，jte 3.2.4 按属性上下文转义它的输出，过滤结果只会作为字面文字出现，
     * 不能跳出属性（无注入），但也不是想要的显示。SafeHtml 只用于元素内容位置（07 文档第 5 节）。
     */
    @Test
    void contentInAttributeIsEscapedByJte() {
        SafeHtml safe = SANITIZER.sanitize("<a href=\"/v/1.html\" title='\" onmouseover=\"x'>x</a>").html();
        assertThat(safe.html()).contains("&quot; onmouseover=&quot;x");
        StringOutput out = new StringOutput();
        engine(Path.of("src", "test", "jte-richtext-attr"), "jte-richtext-attr")
                .render("probe.jte", Map.of("body", content(safe)), out);
        Element div = Parser.parseBodyFragment(out.toString(), "").selectFirst("div");
        assertThat(div).isNotNull();
        assertThat(div.attributes().size()).as("没有跳出 title 属性").isEqualTo(1);
        assertThat(div.attr("title")).isEqualTo(safe.html());
        assertThat(div.children()).isEmpty();
    }
}

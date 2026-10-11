package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.site.web.view.PageChrome;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateException;
import gg.jte.html.HtmlAttribute;
import gg.jte.html.HtmlPolicy;
import gg.jte.html.HtmlPolicyException;
import gg.jte.html.HtmlTag;
import gg.jte.html.OwaspHtmlPolicy;
import gg.jte.html.policy.PreventInlineEventHandlers;
import gg.jte.html.policy.PreventSingleQuotedAttributes;
import gg.jte.resolve.DirectoryCodeResolver;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 用 jte 自己的 HTML 解析器，以比构建（OwaspHtmlPolicy）更严格的站点策略重新编译全部正式模板：
 * 另外禁止内联事件属性、单引号属性、脚本/样式/嵌入类标签、style 属性和 http-equiv。
 * 构建插件的 htmlPolicyClass 必须在 generate-sources 阶段可加载，本工程源码里的策略类做不到，所以在 verify 中以测试执行
 * （04 报告第 3.2 节）。探针模板（src/test/jte-probe/）证明每条规则确实会拒绝违规写法。
 */
class SiteTemplatePolicyTest {

    static final class SiteHtmlPolicy extends OwaspHtmlPolicy {
        SiteHtmlPolicy() {
            addPolicy(new PreventInlineEventHandlers());
            addPolicy(new PreventSingleQuotedAttributes());
            addPolicy(new ForbiddenMarkup());
        }
    }

    /** 骨架页面不需要脚本与嵌入内容；需要时随任务评审修改本策略与 CSP。 */
    static final class ForbiddenMarkup implements HtmlPolicy {
        static final Set<String> TAGS = Set.of("script", "style", "iframe", "frame", "frameset", "object", "embed",
                "applet", "base", "svg", "math", "template");
        static final Set<String> ATTRIBUTES = Set.of("style", "srcdoc", "http-equiv", "formaction");

        @Override
        public void validateHtmlTag(HtmlTag tag) {
            if (TAGS.contains(tag.getName().toLowerCase(Locale.ROOT))) {
                throw new HtmlPolicyException("site 模板不允许 <" + tag.getName() + ">");
            }
        }

        @Override
        public void validateHtmlAttribute(HtmlTag tag, HtmlAttribute attribute) {
            if (ATTRIBUTES.contains(attribute.getName().toLowerCase(Locale.ROOT))) {
                throw new HtmlPolicyException("site 模板不允许属性 " + attribute.getName());
            }
        }
    }

    private static TemplateEngine engine(Path sourceDir, String classDir) {
        TemplateEngine engine = TemplateEngine.create(new DirectoryCodeResolver(sourceDir),
                Path.of("target", classDir), ContentType.Html);
        engine.setHtmlPolicy(new SiteHtmlPolicy());
        engine.setClassPath(classPath());
        return engine;
    }

    /** javac 类路径：jte 运行时 + 本工程的视图模型类。 */
    private static List<String> classPath() {
        return Stream.of(gg.jte.html.HtmlTemplateOutput.class, gg.jte.Content.class, PageChrome.class)
                .map(SiteTemplatePolicyTest::location).distinct().toList();
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void productionTemplatesPassStrictSitePolicy() {
        assertThatCode(() -> engine(Path.of("src", "main", "jte"), "jte-site-policy").precompileAll())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "event-handler, Inline event handlers are not allowed",
            "single-quote, HTML attribute values must be double quoted",
            "script-tag, <script>",
            "style-tag, <style>",
            "iframe-tag, <iframe>",
            "style-attribute, style",
            "meta-refresh, http-equiv",
            "unquoted, Unquoted HTML attribute values are not allowed",
            "output-in-attribute-name, Illegal HTML attribute name"})
    void strictPolicyRejectsProbe(String probe, String expected) {
        assertThatThrownBy(() -> engine(Path.of("src", "test", "jte-probe", probe), "jte-probe-" + probe)
                .precompileAll())
                .isInstanceOf(TemplateException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains(expected));
    }
}

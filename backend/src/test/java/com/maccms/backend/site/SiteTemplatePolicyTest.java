package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.site.web.view.PageChrome;
import gg.jte.CodeResolver;
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
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            this(Set.of());
        }

        /** @param allowed 本组模板额外允许的标签（只用于 B05 的两个已裁定例外，见 {@link #EXCEPTIONS}） */
        SiteHtmlPolicy(Set<String> allowed) {
            addPolicy(new PreventInlineEventHandlers());
            addPolicy(new PreventSingleQuotedAttributes());
            addPolicy(new ForbiddenMarkup(allowed));
        }
    }

    /**
     * 页面不需要脚本与嵌入内容；B05（队长裁定 D-B05-1）只有两个例外，各自单独编译：外层播放页 {@code site/pages/play.jte} 允许
     * 一个同源 {@code <iframe>}；播放器内页 {@code site/player/player.jte} 允许 {@code <script>}，且只能带 nonce 与 src 两个属性
     * （没有内联脚本；SiteGuardrailsTest 另按文本检查每个 script 标签的完整写法）。其余模板仍是全部禁止。
     */
    static final class ForbiddenMarkup implements HtmlPolicy {
        static final Set<String> TAGS = Set.of("script", "style", "iframe", "frame", "frameset", "object", "embed",
                "applet", "base", "svg", "math", "template");
        static final Set<String> ATTRIBUTES = Set.of("style", "srcdoc", "http-equiv", "formaction");
        static final Set<String> SCRIPT_ATTRIBUTES = Set.of("nonce", "src");

        private final Set<String> allowed;

        ForbiddenMarkup(Set<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public void validateHtmlTag(HtmlTag tag) {
            String name = tag.getName().toLowerCase(Locale.ROOT);
            if (TAGS.contains(name) && !allowed.contains(name)) {
                throw new HtmlPolicyException("site 模板不允许 <" + tag.getName() + ">");
            }
        }

        @Override
        public void validateHtmlAttribute(HtmlTag tag, HtmlAttribute attribute) {
            String name = attribute.getName().toLowerCase(Locale.ROOT);
            if (ATTRIBUTES.contains(name)) {
                throw new HtmlPolicyException("site 模板不允许属性 " + attribute.getName());
            }
            if (tag.getName().equalsIgnoreCase("script") && !SCRIPT_ATTRIBUTES.contains(name)) {
                throw new HtmlPolicyException("播放器内页的 <script> 只能有 nonce 与 src 属性：" + attribute.getName());
            }
        }
    }

    /** B05 已裁定的例外：模板 → 额外允许的标签。 */
    static final Map<String, Set<String>> EXCEPTIONS = Map.ofEntries(
            Map.entry("site/pages/play.jte", Set.of("iframe")),
            Map.entry("site/player/player.jte", Set.of("script")),
            // B09（MACC-33，18 文档第 8.1 节）：会员页面末尾的 member.js 只写在 member/script.jte（同样只能带 nonce 与 src），
            // 引用它的会员页面随之按例外编译；这些页面自己没有 <script>（SiteGuardrailsTest 按文本检查）
            Map.entry("site/member/script.jte", Set.of("script")),
            Map.entry("site/pages/userLogin.jte", Set.of("script")),
            Map.entry("site/pages/userReg.jte", Set.of("script")),
            Map.entry("site/pages/userIndex.jte", Set.of("script")),
            Map.entry("site/pages/userInfo.jte", Set.of("script")),
            Map.entry("site/pages/userPortrait.jte", Set.of("script")),
            Map.entry("site/pages/userFindpass.jte", Set.of("script")),
            Map.entry("site/pages/userFindpassMsg.jte", Set.of("script")),
            // B10（MACC-34，19 文档第 8 节）：会员权益与财务页面同样只在末尾引用 member/script.jte
            Map.entry("site/pages/userList.jte", Set.of("script")),
            Map.entry("site/pages/userBuy.jte", Set.of("script")),
            Map.entry("site/pages/userPay.jte", Set.of("script")),
            Map.entry("site/pages/userUpgrade.jte", Set.of("script")),
            Map.entry("site/pages/userPopedom.jte", Set.of("script")),
            Map.entry("site/pages/userCash.jte", Set.of("script")),
            Map.entry("site/pages/userOrder.jte", Set.of("script")));

    private static TemplateEngine engine(Path sourceDir, String classDir) {
        return engine(sourceDir, classDir, Set.of());
    }

    private static TemplateEngine engine(Path sourceDir, String classDir, Set<String> allowed) {
        return engine(new DirectoryCodeResolver(sourceDir), classDir, allowed);
    }

    private static TemplateEngine engine(CodeResolver resolver, String classDir, Set<String> allowed) {
        TemplateEngine engine = TemplateEngine.create(resolver, Path.of("target", classDir), ContentType.Html);
        engine.setHtmlPolicy(new SiteHtmlPolicy(allowed));
        engine.setClassPath(classPath());
        return engine;
    }

    /**
     * 只把 {@code names} 列为"全部模板"的解析器（被它们引用的布局与组件照常可读），使 precompileAll 只编译这一组：
     * 严格策略编译例外以外的全部模板，例外模板各自单独编译。
     */
    private static CodeResolver only(List<String> names) {
        DirectoryCodeResolver dir = new DirectoryCodeResolver(Path.of("src", "main", "jte"));
        return new CodeResolver() {
            @Override
            public String resolve(String name) {
                return dir.resolve(name);
            }

            @Override
            public long getLastModified(String name) {
                return dir.getLastModified(name);
            }

            @Override
            public List<String> resolveAllTemplateNames() {
                return names;
            }
        };
    }

    /** src/main/jte 下全部模板名（相对路径，/ 分隔）。 */
    private static List<String> templateNames() throws IOException {
        Path root = Path.of("src", "main", "jte");
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".jte"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
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
    void productionTemplatesPassStrictSitePolicy() throws IOException {
        List<String> strict = templateNames().stream().filter(n -> !EXCEPTIONS.containsKey(n)).toList();
        assertThat(strict).contains("site/layout/page.jte", "site/pages/detail.jte", "site/player/frame.jte");
        assertThatCode(() -> engine(only(strict), "jte-site-policy", Set.of()).precompileAll())
                .doesNotThrowAnyException();
    }

    @Test
    void approvedExceptionsCompileWithOnlyTheirExtraTag() throws IOException {
        assertThat(templateNames()).containsAll(EXCEPTIONS.keySet());
        for (Map.Entry<String, Set<String>> e : EXCEPTIONS.entrySet()) {
            String tag = e.getValue().iterator().next();
            assertThatCode(() -> engine(only(List.of(e.getKey())), "jte-site-policy-" + tag, e.getValue())
                    .precompileAll()).as(e.getKey()).doesNotThrowAnyException();
        }
        // 例外模板在严格策略下确实会被拒绝（例外是有意的，不是策略漏掉了）
        for (Map.Entry<String, Set<String>> e : EXCEPTIONS.entrySet()) {
            String tag = e.getValue().iterator().next();
            assertThatThrownBy(() -> engine(only(List.of(e.getKey())), "jte-site-policy-strict-" + tag, Set.of())
                    .precompileAll()).as(e.getKey()).isInstanceOf(TemplateException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "script-extra-attribute, 只能有 nonce 与 src 属性",
            "script-event-handler, Inline event handlers are not allowed"})
    void playerScriptPolicyRejectsProbe(String probe, String expected) {
        assertThatThrownBy(() -> engine(Path.of("src", "test", "jte-probe", probe), "jte-probe-" + probe,
                Set.of("script")).precompileAll())
                .isInstanceOf(TemplateException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains(expected));
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

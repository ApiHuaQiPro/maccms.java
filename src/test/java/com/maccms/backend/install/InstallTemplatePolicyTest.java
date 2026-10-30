package com.maccms.backend.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.install.web.view.IndexPage;
import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 安装向导模板策略（31 文档第 7 节，同后台 AdminTemplatePolicyTest 的规则）：旧 HTML 结构原样复用；没有内联脚本与事件属性
 * （{@code <script>} 只带 type/src/data-*，内容为空）；不出现 object/embed/iframe/base 等；{@code <style>} 与 style 属性只是固定文字；
 * 不用 {@code $unsafe}/{@code @raw}/{@code !{}}，原样 HTML 只能是 {@code InstallText.trusted(page.lang().get("语言包键"))}；
 * 模板与新脚本不引用外部主机。
 */
class InstallTemplatePolicyTest {

    private static final Path ROOT = Path.of("src", "main", "jte-install");

    static final class Policy extends OwaspHtmlPolicy {
        Policy() {
            addPolicy(new PreventInlineEventHandlers());
            addPolicy(new PreventSingleQuotedAttributes());
            addPolicy(new HtmlPolicy() {
                private static final Set<String> TAGS = Set.of("object", "embed", "applet", "base", "frame", "frameset",
                        "iframe", "svg", "math", "template");

                @Override
                public void validateHtmlTag(HtmlTag tag) {
                    if (TAGS.contains(tag.getName().toLowerCase(Locale.ROOT))) {
                        throw new HtmlPolicyException("安装模板不允许 <" + tag.getName() + ">");
                    }
                }

                @Override
                public void validateHtmlAttribute(HtmlTag tag, HtmlAttribute attribute) {
                    String name = attribute.getName().toLowerCase(Locale.ROOT);
                    if (Set.of("srcdoc", "formaction", "http-equiv").contains(name)) {
                        throw new HtmlPolicyException("安装模板不允许属性 " + attribute.getName());
                    }
                    if (tag.getName().equalsIgnoreCase("script") && !name.equals("type") && !name.equals("src")
                            && !name.startsWith("data-")) {
                        throw new HtmlPolicyException("<script> 只能有 type、src 与 data-*：" + attribute.getName());
                    }
                }
            });
        }
    }

    private static List<String> templates() throws IOException {
        try (Stream<Path> s = Files.walk(ROOT)) {
            return s.filter(p -> p.toString().endsWith(".jte")).map(p -> ROOT.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    @Test
    void templatesCompileUnderThePolicy() throws IOException {
        List<String> all = templates();
        assertThat(all).containsExactly("install/layout/page.jte", "install/pages/index.jte", "install/pages/jump.jte",
                "install/pages/step2.jte", "install/pages/step3.jte");
        DirectoryCodeResolver dir = new DirectoryCodeResolver(ROOT);
        CodeResolver resolver = new CodeResolver() {
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
                return all;
            }
        };
        TemplateEngine engine = TemplateEngine.create(resolver, Path.of("target", "jte-install-policy"), ContentType.Html);
        engine.setHtmlPolicy(new Policy());
        engine.setClassPath(Stream.of(gg.jte.html.HtmlTemplateOutput.class, gg.jte.Content.class, LegacyLang.class,
                IndexPage.class).map(InstallTemplatePolicyTest::location).distinct().toList());
        assertThatCode(engine::precompileAll).doesNotThrowAnyException();
    }

    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style\\b[^>]*>(.*?)</style>", Pattern.DOTALL);
    private static final Pattern STYLE_ATTR = Pattern.compile("\\sstyle=\"([^\"]*)\"");
    private static final Pattern TRUSTED = Pattern.compile("InstallText\\.trusted\\(page\\.lang\\(\\)\\.get\\(\"[a-z_/]+\"\\)\\)");

    @Test
    void noInlineScriptsStaticStylesAndTrustedHtmlOnlyFromLanguagePacks() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : templates()) {
            String src = Files.readString(ROOT.resolve(name), StandardCharsets.UTF_8);
            Matcher m = SCRIPT.matcher(src);
            while (m.find()) {
                if (!m.group(1).isBlank()) {
                    problems.add(name + ": 内联脚本");
                }
            }
            Matcher st = STYLE_BLOCK.matcher(src);
            while (st.find()) {
                if (st.group(1).contains("$") || st.group(1).contains("@")) {
                    problems.add(name + ": <style> 里有输出");
                }
            }
            Matcher sa = STYLE_ATTR.matcher(src);
            while (sa.find()) {
                if (sa.group(1).contains("$")) {
                    problems.add(name + ": style 属性里有输出");
                }
            }
            for (String banned : List.of("$unsafe", "@raw", "!{")) {
                if (src.contains(banned)) {
                    problems.add(name + ": " + banned);
                }
            }
            long calls = src.split("InstallText\\.trusted\\(", -1).length - 1;
            if (calls != TRUSTED.matcher(src).results().count()) {
                problems.add(name + ": InstallText.trusted 的参数只能是 page.lang().get(\"语言包键\")");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void templatesAndScriptsHaveNoExternalHosts() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src", "main", "resources", "static", "install-assets"))) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        files.add(Path.of("php", "static", "css", "install.css"));
        templates().forEach(t -> files.add(ROOT.resolve(t)));
        Pattern host = Pattern.compile("(?i)(https?:)?//[a-z0-9-]+\\.[a-z0-9.-]+");
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            Matcher m = host.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                // 跳转页的 XHTML 文档类型声明与命名空间是标识符，不是加载的资源
                if (!m.group().startsWith("http://www.w3.org")) {
                    hits.add(f + ": " + m.group());
                }
            }
        }
        assertThat(hits).isEmpty();
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}

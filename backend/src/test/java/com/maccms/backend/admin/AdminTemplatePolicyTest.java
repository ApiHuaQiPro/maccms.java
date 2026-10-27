package com.maccms.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
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
 * 后台模板策略（21 文档第 8 节）：旧后台的 HTML 结构原样复用，但
 * <ul>
 * <li>没有内联脚本与内联事件属性：{@code <script>} 只能带 {@code type}、{@code src} 与 {@code data-*}，且内容为空（页面脚本在
 * static/admin-assets/）；</li>
 * <li>不出现 {@code <object>}、{@code <embed>}、{@code <base>}、{@code srcdoc}、{@code formaction}、{@code http-equiv}；{@code <iframe>}
 * 只在框架页（欢迎页标签）与卡密列表（B14：旧模板导出用的隐藏 {@code <iframe id="if" width="0" height="0">}，模板里不带地址，由
 * card-index.js 写入本站的导出地址，{@link #FRAME_EXCEPTIONS}）；</li>
 * <li>{@code <style>} 与 style 属性只能是固定文字（框架页侧栏的 display:none 由一个固定的三元表达式写出）；</li>
 * <li>不用 {@code $unsafe}、{@code @raw}、{@code !{}}；原样输出 HTML 只能经 {@code AdminText.trusted(page.lang().get("…"))}（语言包里自带
 * {@code <br>} 的文字）。</li>
 * </ul>
 */
class AdminTemplatePolicyTest {

    private static final Path ROOT = Path.of("src", "main", "jte-admin");
    /** 允许 {@code <iframe>} 的模板：框架页（B12），卡密列表的导出目标（B14，只能是固定的一段 {@link #CARD_EXPORT_FRAME}）。 */
    static final Set<String> FRAME_EXCEPTIONS = Set.of("admin/pages/index.jte", "admin/pages/card/index.jte");
    static final String CARD_EXPORT_FRAME = "<iframe id=\"if\" width=\"0\" height=\"0\"></iframe>";

    static final class AdminHtmlPolicy extends OwaspHtmlPolicy {
        AdminHtmlPolicy(boolean frame) {
            addPolicy(new PreventInlineEventHandlers());
            addPolicy(new PreventSingleQuotedAttributes());
            addPolicy(new Forbidden(frame));
        }
    }

    static final class Forbidden implements HtmlPolicy {
        private static final Set<String> TAGS = Set.of("object", "embed", "applet", "base", "frame", "frameset", "svg",
                "math", "template");
        private static final Set<String> ATTRIBUTES = Set.of("srcdoc", "formaction", "http-equiv");
        private final boolean frame;

        Forbidden(boolean frame) {
            this.frame = frame;
        }

        @Override
        public void validateHtmlTag(HtmlTag tag) {
            String name = tag.getName().toLowerCase(Locale.ROOT);
            if (TAGS.contains(name) || (name.equals("iframe") && !frame)) {
                throw new HtmlPolicyException("后台模板不允许 <" + tag.getName() + ">");
            }
        }

        @Override
        public void validateHtmlAttribute(HtmlTag tag, HtmlAttribute attribute) {
            String name = attribute.getName().toLowerCase(Locale.ROOT);
            if (ATTRIBUTES.contains(name)) {
                throw new HtmlPolicyException("后台模板不允许属性 " + attribute.getName());
            }
            if (tag.getName().equalsIgnoreCase("script") && !name.equals("type") && !name.equals("src")
                    && !name.startsWith("data-")) {
                throw new HtmlPolicyException("<script> 只能有 type、src 与 data-* 属性：" + attribute.getName());
            }
        }
    }

    private static List<String> templates() throws IOException {
        try (Stream<Path> s = Files.walk(ROOT)) {
            return s.filter(p -> p.toString().endsWith(".jte")).map(p -> ROOT.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    /** 只把 {@code names} 列为"全部模板"的解析器（被引用的布局照常可读），使 precompileAll 只编译这一组。 */
    private static CodeResolver only(List<String> names) {
        DirectoryCodeResolver dir = new DirectoryCodeResolver(ROOT);
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

    private static TemplateEngine engine(String dir, boolean frame, List<String> names) {
        TemplateEngine engine = TemplateEngine.create(only(names), Path.of("target", dir), ContentType.Html);
        engine.setHtmlPolicy(new AdminHtmlPolicy(frame));
        engine.setClassPath(Stream.of(gg.jte.html.HtmlTemplateOutput.class, gg.jte.Content.class, AdminHead.class,
                LegacyLang.class).map(AdminTemplatePolicyTest::location).distinct().toList());
        return engine;
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void templatesCompileUnderTheAdminPolicy() throws IOException {
        List<String> all = templates();
        assertThat(all).contains("admin/layout/page.jte", "admin/pages/login.jte", "admin/pages/index.jte",
                "admin/pages/welcome.jte", "admin/pages/quickmenu.jte", "admin/pages/adminList.jte",
                "admin/pages/adminInfo.jte", "admin/pages/jump.jte", "admin/pages/notice.jte");
        for (String name : all) {
            boolean frame = FRAME_EXCEPTIONS.contains(name);
            assertThatCode(() -> engine("jte-admin-policy-" + name.replace('/', '_'), frame, List.of(name)).precompileAll())
                    .as(name).doesNotThrowAnyException();
        }
        // 框架页与卡密列表的 <iframe> 是仅有的例外：在一般策略下确实被拒绝
        for (String name : FRAME_EXCEPTIONS) {
            assertThatThrownBy(() -> engine("jte-admin-policy-frame-strict-" + name.replace('/', '_'), false, List.of(name))
                    .precompileAll()).as(name).isInstanceOf(TemplateException.class);
        }
        // 卡密列表只有那一段不带地址的隐藏 iframe
        String card = Files.readString(ROOT.resolve("admin/pages/card/index.jte"), StandardCharsets.UTF_8);
        assertThat(card.split("<iframe", -1).length - 1).isEqualTo(1);
        assertThat(card).contains(CARD_EXPORT_FRAME);
    }

    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style\\b[^>]*>(.*?)</style>", Pattern.DOTALL);
    /** 唯一允许的动态 style：框架页侧栏除第一组外隐藏（旧模板的两个分支合成一个固定的三元表达式）。 */
    private static final String SIDEBAR_STYLE = "style=\"${v.index() == 1 ? null : \"display:none;\"}\"";
    private static final Pattern STYLE_ATTR = Pattern.compile("\\sstyle=\"");
    /**
     * B13：按条件二选一的固定样式（旧模板 {@code {if …}style="display:none"{/if}} 一类写法）：{@code style="${条件 ? "字面量" : null}"}，
     * 两个分支都是不含输出的字面量或 null，条件只是方法调用与成员访问（B15 起条件里可以有字符串字面量与 {@code ||}/{@code &&}，如
     * {@code page.eq(page.at("config.upload.mode"), "Ftp")}；输出仍只能是两个固定分支之一）。
     */
    private static final Pattern STYLE_CHOICE = Pattern.compile(
            "style=\"\\$\\{(?:[\\w.()!|&, ]|\"[^\"$@{}?]*\")+ \\? (?:null|\"[^\"$@{}]*\") : (?:null|\"[^\"$@{}]*\")}\"");
    private static final Pattern TRUSTED = Pattern.compile(
            "AdminText\\.trusted\\(page\\.lang\\(\\)\\.get\\(\"[a-z_/]+\"\\)\\)");

    @Test
    void noInlineScriptsStylesOnlyStaticAndTrustedHtmlOnlyFromLanguagePacks() throws IOException {
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
                // style 属性的值要么不含任何输出，要么正好是侧栏那一个固定表达式
                int start = sa.end();
                boolean sidebar = src.startsWith(SIDEBAR_STYLE, sa.start() + 1)
                        || STYLE_CHOICE.matcher(src).region(sa.start() + 1, src.length()).lookingAt();
                boolean literal = src.indexOf('"', start) > start - 1 && !src.substring(start, src.indexOf('"', start)).contains("$");
                if (!sidebar && !literal) {
                    problems.add(name + ": style 属性里有输出");
                }
            }
            for (String banned : List.of("$unsafe", "@raw", "!{")) {
                if (src.contains(banned)) {
                    problems.add(name + ": " + banned);
                }
            }
            long calls = src.split("AdminText\\.trusted\\(", -1).length - 1;
            if (calls != TRUSTED.matcher(src).results().count()) {
                problems.add(name + ": AdminText.trusted 的参数只能是 page.lang().get(\"语言包键\")");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void pageScriptsAndLegacyAssetsHaveNoExternalHosts() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src", "main", "resources", "static", "admin-assets"))) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        files.add(Path.of("..", "static", "js", "admin_common.js"));
        files.add(Path.of("..", "static", "css", "admin_style.css"));
        try (Stream<Path> s = templates().stream().map(ROOT::resolve)) {
            s.forEach(files::add);
        }
        Pattern host = Pattern.compile("(?i)(https?:)?//[a-z0-9-]+\\.[a-z0-9.-]+");
        // B15（24 文档第 8 节）：系统配置各页里旧模板自带的"去服务商注册/申请"按钮，是管理员点击后在新标签页打开的链接（a href，target=_blank），
        // 不是页面加载的资源；地址原样保留，只允许出现在这几个模板里
        java.util.Map<String, java.util.Set<String>> navigation = java.util.Map.ofEntries(
                java.util.Map.entry("configconnect.jte", java.util.Set.of("http://connect.qq.com", "https://open.weixin.qq.com")),
                java.util.Map.entry("pay_alipay.jte", java.util.Set.of("https://open.alipay.com")),
                java.util.Map.entry("pay_codepay.jte", java.util.Set.of("https://api.xiuxiu888.com")),
                java.util.Map.entry("pay_weixin.jte", java.util.Set.of("https://pay.weixin.qq.com")),
                java.util.Map.entry("pay_zhapay.jte", java.util.Set.of("https://www.zhapay.com")),
                java.util.Map.entry("sms_aliyun.jte", java.util.Set.of("https://www.aliyun.com")),
                java.util.Map.entry("sms_qcloud.jte", java.util.Set.of("https://cloud.tencent.com")),
                java.util.Map.entry("upload_alibaba.jte", java.util.Set.of("http://alibaba.com")),
                java.util.Map.entry("upload_qiniu.jte", java.util.Set.of("https://www.qiniu.com")),
                java.util.Map.entry("upload_uomg.jte", java.util.Set.of("http://api.uomg.com")),
                java.util.Map.entry("upload_upyun.jte", java.util.Set.of("https://www.upyun.com")),
                java.util.Map.entry("upload_weibo.jte", java.util.Set.of("https://www.weibo.com")));
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            Matcher m = host.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                boolean nav = f.toString().replace('\\', '/').contains("/admin/pages/system/")
                        && navigation.getOrDefault(f.getFileName().toString(), java.util.Set.of()).contains(m.group());
                // B17：自定义规则表单里旧模板自带的采集地址示例文字（"如：http://www.phpcms.cn/help/rumen/(*).html"），不是链接也不是资源
                boolean example = f.toString().replace('\\', '/').endsWith("/admin/pages/cj/info.jte")
                        && m.group().equals("http://www.phpcms.cn");
                // 跳转页的 XHTML 文档类型声明与命名空间是标识符，不是加载的资源
                if (!m.group().startsWith("http://www.w3.org") && !nav && !example) {
                    hits.add(f + ": " + m.group());
                }
            }
        }
        assertThat(hits).isEmpty();
    }
}

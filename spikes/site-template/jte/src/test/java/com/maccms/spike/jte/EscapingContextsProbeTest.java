package com.maccms.spike.jte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateException;
import gg.jte.html.OwaspHtmlPolicy;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import java.nio.file.Path;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * 逐个上下文探测默认转义。探针模板只在 src/test/jte-probe，测试时动态编译，不进入正式模板目录。
 * 输出以 SPIKE-RESULT 打印，供报告引用。
 */
class EscapingContextsProbeTest {

    static final String PAYLOAD = "</script><script>alert(1)</script>\"'&<b>中文";
    static final char TAB = '\t';

    private static TemplateEngine engine(String classDir) {
        TemplateEngine engine = TemplateEngine.create(new DirectoryCodeResolver(Path.of("src/test/jte-probe")),
                Path.of("target", classDir), ContentType.Html);
        engine.setHtmlPolicy(new OwaspHtmlPolicy());
        return engine;
    }

    @Test
    void eachContextIsEscapedForItsSyntax() {
        String html = render("jte-probe-classes", PAYLOAD);
        html.lines().forEach(l -> System.out.println("SPIKE-RESULT jte probe " + l.strip()));

        assertThat(line(html, "id=\"text\"")).doesNotContain("<script>").doesNotContain("<b>").contains("中文");
        assertThat(line(html, "id=\"attr\"")).doesNotContain("\"'&<");
        // 事件属性：引号按 JS 转义为 \x22 / \x27，不能用字面引号跳出
        assertThat(line(html, "id=\"event\"")).contains("\\x22\\x27");
        // <script> 块：</script> 被写成 <\/script>，不能提前闭合脚本块
        String js = line(html, "id=\"js\"");
        assertThat(js.indexOf("</script>")).isEqualTo(js.lastIndexOf("</script>"));
    }

    /**
     * 风险刻画（jte 3.2.4 实测）：事件属性按 JS 转义，但不转义 &amp;。浏览器先对属性值做 HTML 实体解码再交给 JS，
     * 于是 &amp;#39; 变回单引号，跳出 JS 字符串。正式模板由 SitePolicyCompileTest（PreventInlineEventHandlers）
     * 与 TemplateGuardTest 禁止在事件属性中放数据。
     */
    @Test
    void entityInEventHandlerAttributeBreaksOutOfJsString() {
        String html = render("jte-probe-classes-3", "&#39;);alert(1);//");
        String decoded = Jsoup.parse(html).getElementById("event").attr("onclick");
        System.out.println("SPIKE-RESULT jte event-attr entity raw=" + line(html, "id=\"event\"").strip()
                + " decodedHandler=" + decoded);

        assertThat(decoded).isEqualTo("go('');alert(1);//')");
    }

    /**
     * 风险刻画（jte 3.2.4 实测）：jte 只在 &lt;a href&gt; 以 "javascript:"（忽略大小写与前导空白）开头时丢弃输出；
     * 中间夹制表符的写法不被识别，而浏览器解析 URL 时会删除制表符。URL 安全必须由 URL 生成器的协议白名单保证。
     */
    @Test
    void javascriptHrefFilterOnlyMatchesLiteralPrefix() {
        String tabbedInput = "java" + TAB + "script:alert(9)";
        String plainHref = Jsoup.parse(render("jte-probe-classes-4", " JavaScript:alert(8)"))
                .getElementById("href").attr("href");
        String tabbedHref = Jsoup.parse(render("jte-probe-classes-4", tabbedInput)).getElementById("href").attr("href");
        System.out.println("SPIKE-RESULT jte href ' JavaScript:alert(8)' -> '" + plainHref
                + "'; 'java<TAB>script:alert(9)' -> '" + tabbedHref.replace(String.valueOf(TAB), "<TAB>") + "'");

        assertThat(plainHref).isEmpty();
        assertThat(tabbedHref).isEqualTo(tabbedInput);
    }

    @Test
    void owaspPolicyRejectsUnquotedAttributeAtCompileTime() {
        assertThatThrownBy(() -> engine("jte-probe-classes-2").render("unquoted.jte", Map.of("x", "a"), new StringOutput()))
                .isInstanceOf(TemplateException.class)
                .satisfies(e -> System.out.println("SPIKE-RESULT jte unquoted attribute -> " + e.getMessage()));
    }

    private static String render(String classDir, String x) {
        StringOutput out = new StringOutput();
        engine(classDir).render("contexts.jte", Map.of("x", x), out);
        return out.toString();
    }

    private static String line(String html, String id) {
        return html.lines().filter(l -> l.contains(id)).findFirst().orElseThrow();
    }
}

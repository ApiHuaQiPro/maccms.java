package com.maccms.spike.thymeleaf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 逐个上下文探测默认转义（探针模板只在 src/test/resources/probe，不进入正式模板目录）。
 * 输出以 SPIKE-RESULT 打印，供报告引用。
 */
class EscapingContextsProbeTest {

    static final String PAYLOAD = "</script><script>alert(1)</script>\"'&<b>中文";

    static SpringTemplateEngine engine;

    @BeforeAll
    static void engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("probe/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    @Test
    void eachContextIsEscapedForItsSyntax() {
        String html = engine.process("contexts", new Context(null, Map.of("x", PAYLOAD)));
        html.lines().filter(l -> l.contains("id=")).forEach(l -> System.out.println("SPIKE-RESULT thymeleaf probe " + l.strip()));

        assertThat(line(html, "id=\"text\""))
                .contains("&lt;/script&gt;&lt;script&gt;alert(1)&lt;/script&gt;&quot;&#39;&amp;&lt;b&gt;中文");
        assertThat(line(html, "id=\"inline\"")).doesNotContain("<script>").doesNotContain("<b>");
        assertThat(line(html, "id=\"attr\"")).doesNotContain("\"'&<").contains("&quot;");
        // JavaScript 内联：作为 JS 字符串字面量输出，</script> 不能提前闭合脚本块
        String js = line(html, "id=\"js\"");
        assertThat(js.indexOf("</script>")).isEqualTo(js.lastIndexOf("</script>"));
    }

    @Test
    void stringInEventHandlerAttributeIsRejectedAtRenderTime() {
        assertThatThrownBy(() -> engine.process("event-attr", new Context(null, Map.of("x", "alert(1)"))))
                .isInstanceOf(TemplateProcessingException.class)
                .satisfies(e -> System.out.println("SPIKE-RESULT thymeleaf th:onclick(String) -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage()));
        // 数字仍允许（Thymeleaf 3.0.12+ 的限制只放行数字/布尔）
        assertThat(engine.process("event-attr", new Context(null, Map.of("x", 42)))).contains("onclick=\"42\"");
    }

    private static String line(String html, String id) {
        return html.lines().filter(l -> l.contains(id)).findFirst().orElseThrow();
    }
}

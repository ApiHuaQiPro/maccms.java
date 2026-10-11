package com.maccms.spike.jte;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateException;
import gg.jte.html.OwaspHtmlPolicy;
import gg.jte.html.policy.PreventInlineEventHandlers;
import gg.jte.html.policy.PreventSingleQuotedAttributes;
import gg.jte.resolve.DirectoryCodeResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 比构建默认（OwaspHtmlPolicy）更严格的站点策略：再禁止内联事件处理器与单引号属性。
 * 用 jte 自己的 HTML 解析器在 verify 中重新编译全部正式模板，比正则护栏更可靠。
 * （jte-maven-plugin 的 htmlPolicyClass 需要在生成阶段就能加载该类，放在本工程源码里做不到，故以测试执行。）
 */
class SitePolicyCompileTest {

    static final class SiteHtmlPolicy extends OwaspHtmlPolicy {
        SiteHtmlPolicy() {
            addPolicy(new PreventInlineEventHandlers());
            addPolicy(new PreventSingleQuotedAttributes());
        }
    }

    private static TemplateEngine engine(String sourceDir, String classDir) {
        TemplateEngine engine = TemplateEngine.create(new DirectoryCodeResolver(Path.of(sourceDir)),
                Path.of("target", classDir), ContentType.Html);
        engine.setHtmlPolicy(new SiteHtmlPolicy());
        engine.setClassPath(JteTestClasspath.entries());
        return engine;
    }

    @Test
    void productionTemplatesPassStrictPolicy() {
        assertThatCode(() -> engine("src/main/jte", "jte-policy-main").precompileAll()).doesNotThrowAnyException();
    }

    @Test
    void strictPolicyRejectsEventHandlerProbe() {
        assertThatThrownBy(() -> engine("src/test/jte-probe", "jte-policy-probe").precompileAll())
                .isInstanceOf(TemplateException.class)
                .satisfies(e -> System.out.println("SPIKE-RESULT jte strict policy on probe -> "
                        + e.getMessage().lines().findFirst().orElse("")));
    }
}

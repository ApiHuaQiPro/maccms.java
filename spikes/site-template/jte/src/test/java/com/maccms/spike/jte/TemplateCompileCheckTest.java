package com.maccms.spike.jte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateException;
import gg.jte.resolve.DirectoryCodeResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 字段引用错误在编译期失败：用与 jte-maven-plugin 相同的编译器动态编译 src/test/jte-broken 下故意写错的模板。
 * 构建级证据（mvnw -Pmismatch verify 直接 BUILD FAILURE）见 README 与 04 报告。
 */
class TemplateCompileCheckTest {

    @Test
    void misspelledAccessorFailsCompilationWithoutRendering() {
        TemplateEngine engine = TemplateEngine.create(new DirectoryCodeResolver(Path.of("src/test/jte-broken")),
                Path.of("target", "jte-broken-classes"), ContentType.Html);
        engine.setClassPath(JteTestClasspath.entries());

        // precompileAll 只编译、不渲染：错误与数据、分支是否执行无关
        assertThatThrownBy(engine::precompileAll)
                .isInstanceOf(TemplateException.class)
                .satisfies(e -> {
                    System.out.println("SPIKE-RESULT jte broken template -> " + e.getMessage().lines()
                            .filter(l -> l.contains("error")).map(String::strip).toList());
                    // 必须是"找不到 nmae()"这一个错误，而不是类路径等其他编译问题
                    assertThat(e.getMessage()).contains("cannot find symbol").contains("nmae()")
                            .doesNotContain("does not exist");
                });
    }
}

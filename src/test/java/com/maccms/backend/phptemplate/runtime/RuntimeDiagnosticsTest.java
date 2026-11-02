package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.phptemplate.runtime.TemplateUnsupportedException.Kind;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * C-23（runtime 定位部分）{@link TemplateUnsupportedException}、BARE-3 {@link BareIdentifiers} 与
 * {@link PhpAst} 本批基础节点的约束。
 */
class RuntimeDiagnosticsTest {

    private static final Path RUNTIME_SOURCES = Path.of("src", "main", "java", "com", "maccms", "backend", "phptemplate", "runtime");

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- TemplateUnsupportedException ----

    @Test
    void kindCodesAreStable() {
        assertThat(Arrays.stream(Kind.values()).map(Kind::code))
                .containsExactly("open_tag", "token", "unterminated", "interpolation", "bare_identifier");
    }

    @Test
    void exceptionCarriesKindOffsetAndSnippetFromSourceBytes() {
        byte[] src = b("<?php echo PHP_EOL; ?>\nsecond");
        TemplateUnsupportedException e = new TemplateUnsupportedException(Kind.BARE_IDENTIFIER, src, 11);
        assertThat(e.kind()).isEqualTo(Kind.BARE_IDENTIFIER);
        assertThat(e.offset()).isEqualTo(11);
        assertThat(e.snippet()).as("片段到行尾为止").isEqualTo("PHP_EOL; ?>");
        assertThat(e.getMessage()).isEqualTo("bare_identifier at offset 11: PHP_EOL; ?>");
    }

    @Test
    void snippetIsBoundedAndNeverSplitsUtf8() {
        byte[] src = b("x".repeat(10) + "上".repeat(40));
        TemplateUnsupportedException e = new TemplateUnsupportedException(Kind.TOKEN, src, 10);
        assertThat(e.snippet().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(TemplateUnsupportedException.SNIPPET_MAX_BYTES);
        assertThat(e.snippet()).matches("上+").doesNotContain("�");
        TemplateUnsupportedException cr = new TemplateUnsupportedException(Kind.TOKEN, b("ab\rcd"), 0);
        assertThat(cr.snippet()).isEqualTo("ab");
        TemplateUnsupportedException atEnd = new TemplateUnsupportedException(Kind.UNTERMINATED, b("ab"), 2);
        assertThat(atEnd.snippet()).isEmpty();
    }

    @Test
    void exceptionRejectsInvalidOffsets() {
        assertThatThrownBy(() -> new TemplateUnsupportedException(Kind.TOKEN, b("ab"), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateUnsupportedException(Kind.TOKEN, b("ab"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exceptionIsUncheckedAndHasNoMutableState() {
        assertThat(RuntimeException.class).isAssignableFrom(TemplateUnsupportedException.class);
        for (Field f : TemplateUnsupportedException.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(f.getModifiers())).as(f.getName()).isTrue();
        }
    }

    // ---- BareIdentifiers（BARE-3） ----

    @Test
    void registeredBareNamesAreExactlyColor() {
        assertThat(BareIdentifiers.REGISTERED).isEqualTo(Set.of("color"));
        assertThat(BareIdentifiers.REGISTERED).doesNotContain("COLOR", "Color", "true", "null");
        assertThatThrownBy(() -> BareIdentifiers.REGISTERED.add("PHP_EOL")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void bareIdentifiersCannotBeExtendedOrReassigned() throws Exception {
        assertThat(Modifier.isFinal(BareIdentifiers.class.getModifiers())).isTrue();
        assertThat(BareIdentifiers.class.getDeclaredConstructors()).allSatisfy(c ->
                assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
        Field f = BareIdentifiers.class.getDeclaredField("REGISTERED");
        assertThat(Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())
                && Modifier.isFinal(f.getModifiers())).isTrue();
        assertThat(BareIdentifiers.class.getDeclaredFields()).hasSize(1);
    }

    // ---- PhpAst 基础 ----

    @Test
    void inlineHtmlKeepsRawBytesDefensively() {
        byte[] raw = b("a\r\n上");
        PhpAst.InlineHtml h = new PhpAst.InlineHtml(4, raw);
        raw[0] = 'X';
        assertThat(h.bytes()).isEqualTo(b("a\r\n上"));
        h.bytes()[0] = 'Y';
        assertThat(h.bytes()).isEqualTo(b("a\r\n上"));
        assertThat(h).isEqualTo(new PhpAst.InlineHtml(4, b("a\r\n上")));
        assertThat(h.hashCode()).isEqualTo(new PhpAst.InlineHtml(4, b("a\r\n上")).hashCode());
        assertThat(h).isNotEqualTo(new PhpAst.InlineHtml(5, b("a\r\n上")));
    }

    @Test
    void stringLiteralComparesByBytes() {
        assertThat(new PhpAst.StringLiteral(1, b("x"))).isEqualTo(new PhpAst.StringLiteral(1, b("x")));
        assertThat(new PhpAst.StringLiteral(1, b("x"))).isNotEqualTo(new PhpAst.StringLiteral(1, b("y")));
    }

    @Test
    void nodesRejectNegativeOffsets() {
        assertThatThrownBy(() -> new PhpAst.InlineHtml(-1, new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhpAst.IntLiteral(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhpAst.Variable(0, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bareConstOnlyForRegisteredNames() {
        assertThat(new PhpAst.BareConst(11, "color").name()).isEqualTo("color");
        for (String n : List.of("COLOR", "Color", "PHP_EOL", "true", "\\color")) {
            assertThatThrownBy(() -> new PhpAst.BareConst(11, n)).as(n).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void programListIsImmutable() {
        PhpAst.Program p = new PhpAst.Program(new ArrayList<>(List.of(new PhpAst.InlineHtml(0, b("a")))));
        assertThatThrownBy(() -> p.statements().add(new PhpAst.InlineHtml(1, b("b"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- runtime 隔离（AC-307A-3）：本批新类不读配置、宿主、反射或日志 ----

    @Test
    void newRuntimeClassesDoNotTouchHostConfigReflectionOrLogging() throws IOException {
        Pattern forbidden = Pattern.compile(
                "System\\.(getProperty|getenv|getProperties)|java\\.lang\\.reflect|Class\\.forName|getDeclared"
                        + "|setAccessible|ProcessBuilder|Runtime\\.getRuntime|java\\.io\\.File|java\\.nio\\.file"
                        + "|java\\.util\\.logging|System\\.(out|err)|Thread\\.");
        List<String> bad = new ArrayList<>();
        for (String name : List.of("PhpLexer.java", "PhpAst.java", "TemplateUnsupportedException.java", "BareIdentifiers.java")) {
            String src = Files.readString(RUNTIME_SOURCES.resolve(name), StandardCharsets.UTF_8);
            Matcher m = forbidden.matcher(src);
            while (m.find()) {
                bad.add(name + ": " + m.group());
            }
        }
        assertThat(bad).isEmpty();
    }
}

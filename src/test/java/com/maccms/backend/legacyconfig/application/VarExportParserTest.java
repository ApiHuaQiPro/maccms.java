package com.maccms.backend.legacyconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.LegacyConfigException.Reason;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpBool;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpFloat;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpInt;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpNull;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpString;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link VarExportParser} 的接受/拒绝规则。与 PHP 实际结果的逐项对拍见 {@link LegacyConfigGoldenVectorsTest}。 */
class VarExportParserTest {

    /** 拒绝信息里不得出现的合成标记（证明错误信息不回显文件内容）。 */
    private static final String MARKER = "test-only-not-a-secret";

    private static PhpArray parse(String text) {
        return VarExportParser.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void rejects(String text, Reason reason) {
        assertThatThrownBy(() -> parse(text)).as(text)
                .isInstanceOf(LegacyConfigException.class)
                .satisfies(e -> assertThat(((LegacyConfigException) e).reason()).as(text).isEqualTo(reason))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(MARKER));
    }

    @Test
    void parsesVarExportOutput() {
        PhpArray a = parse("<?php\nreturn array (\n  'site' => \n  array (\n    'site_name' => 'x\\'y\\\\z',\n"
                + "    'port' => 3306,\n    'ratio' => -0.5,\n    'on' => true,\n    'none' => NULL,\n  ),\n  0 => 'a',\n);");
        assertThat(a.path("site", "site_name")).isEqualTo(new PhpString("x'y\\z"));
        assertThat(a.path("site", "port")).isEqualTo(new PhpInt(3306));
        assertThat(a.path("site", "ratio")).isEqualTo(new PhpFloat(-0.5));
        assertThat(a.path("site", "on")).isEqualTo(new PhpBool(true));
        assertThat(a.path("site", "none")).isSameAs(PhpNull.INSTANCE);
        assertThat(a.get(0)).isEqualTo(new PhpString("a"));
        assertThat(a.get("0")).isEqualTo(new PhpString("a"));
        assertThat(a.entries().keySet()).containsExactly("site", 0L);
    }

    @Test
    void singleQuotedEscapesMatchPhp() {
        // PHP 单引号字符串只认 \\ 与 \'，其他反斜线原样保留
        // PHP 源码：['a' => 'x\ny\tz\\\'', 'b' => '\\', 'c' => 'end\\\\']
        PhpArray a = parse("<?php return ['a' => 'x\\ny\\tz\\\\\\'', 'b' => '\\\\', 'c' => 'end\\\\\\\\'];");
        assertThat(a.get("a")).isEqualTo(new PhpString("x\\ny\\tz\\'"));
        assertThat(a.get("b")).isEqualTo(new PhpString("\\"));
        assertThat(a.get("c")).isEqualTo(new PhpString("end\\\\"));
        // '\' 中的 \' 是转义的引号，字符串未闭合
        rejects("<?php return ['\\'];", LegacyConfigException.Reason.SYNTAX);
    }

    @Test
    void keysAreNormalizedLikePhp() {
        PhpArray a = parse("<?php return ['1' => 'a', '01' => 'b', '-0' => 'c', '-5' => 'd', ' 1' => 'e', "
                + "'9223372036854775807' => 'f', '9223372036854775808' => 'g', 1 => 'overwrite'];");
        assertThat(a.entries().keySet()).containsExactly(1L, "01", "-0", -5L, " 1", Long.MAX_VALUE,
                "9223372036854775808");
        assertThat(a.get(1)).isEqualTo(new PhpString("overwrite"));
    }

    @Test
    void autoIndexFollowsLargestIntegerKey() {
        PhpArray a = parse("<?php return ['a', 5 => 'b', 'c', '2' => 'd', 'e', 'k' => 'f', 'g'];");
        assertThat(a.entries().keySet()).containsExactly(0L, 5L, 6L, 2L, 7L, "k", 8L);
    }

    @Test
    void autoIndexAfterOnlyNegativeKeysIsRejectedBecausePhp7And8Differ() {
        rejects("<?php return [-5 => 'a', 'b'];", Reason.UNSUPPORTED_CONSTRUCT);
        assertThat(parse("<?php return [-5 => 'a', 3 => 'b', 'c'];").entries().keySet()).containsExactly(-5L, 3L, 4L);
    }

    @Test
    void acceptsCommentsShortSyntaxCaseInsensitiveKeywordsAndClosingTag() {
        PhpArray a = parse("<?PHP\r\n// 注释\r\n/* 块 'x' => 1 */\r\nRETURN [ 'a' => ARRAY ( TRUE , False , NuLL , ) , ] ;\r\n?>\r\n\r\n");
        assertThat(((PhpArray) a.get("a")).entries().values())
                .containsExactly(new PhpBool(true), new PhpBool(false), PhpNull.INSTANCE);
        assertThat(parse("<?php return array();").size()).isZero();
        assertThat(parse("<?php return[];").size()).isZero();
    }

    @Test
    void numbers() {
        PhpArray a = parse("<?php return [0, -0, 9223372036854775807, -9223372036854775807, 1.0E+25, 1.5E-7, -0.0, "
                + "00.5, 1e3, 5.0E-324];");
        assertThat(a.entries().values()).containsExactly(new PhpInt(0), new PhpInt(0), new PhpInt(Long.MAX_VALUE),
                new PhpInt(-Long.MAX_VALUE), new PhpFloat(1.0E25), new PhpFloat(1.5E-7), new PhpFloat(-0.0),
                new PhpFloat(0.5), new PhpFloat(1000.0), new PhpFloat(4.9E-324));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<?php return FOO;",
            "<?php return ['a' => PHP_INT_MAX];",
            "<?php return ['a' => NAN];",
            "<?php return ['a' => INF];",
            "<?php return ['a' => -INF];",
            "<?php return ['a' => strtoupper('x')];",
            "<?php return ['a' => system('test-only-not-a-secret')];",
            "<?php return ['a' => include 'x.php'];",
            "<?php return ['a' => eval('return 1;')];",
            "<?php return ['a' => `test-only-not-a-secret`];",
            "<?php return ['a' => $x];",
            "<?php return ['a' => \"test-only-not-a-secret\"];",
            "<?php return ['a' => \"{$x}\"];",
            "<?php return ['a' => <<<EOT\ntest-only-not-a-secret\nEOT\n];",
            "<?php return ['a' => 'x' . 'test-only-not-a-secret'];",
            "<?php return ['a' => '' . \"\\0\" . ''];",
            "<?php return ['a' => -9223372036854775807-1];",
            "<?php return ['a' => 1 + 1];",
            "<?php return ['a' => (int) '1'];",
            "<?php return ['a' => (object) array()];",
            "<?php return ['a' => \\Foo::__set_state(array())];",
            "<?php return ['a' => new Foo()];",
            "<?php return ['a' => 0x1F];",
            "<?php return ['a' => 0b101];",
            "<?php return ['a' => 010];",
            "<?php return ['a' => 1_000];",
            "<?php return [1.5 => 'x'];",
            "<?php return [true => 'x'];",
            "<?php return [null => 'x'];",
            "<?php return [[] => 'x'];",
            "<?php # test-only-not-a-secret\nreturn [];",
            "<?php return [#[Attr]\n];",
            "<?php return [ // ?> test-only-not-a-secret\n];",
            "<?php return []; ?>test-only-not-a-secret",
            "<?php return []; echo 'test-only-not-a-secret';",
            "<?php $a = 1; return [];",
            "<?php echo 1; return [];",
            "<?php return ('x');",
            "<?php return - 1;",
            "<?php return 'scalar';",
            "<?php return 1;",
            "<?php return true;"})
    void rejectsNonVarExportConstructs(String text) {
        rejects(text, Reason.UNSUPPORTED_CONSTRUCT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "return [];",
            " <?php return [];",
            "test-only-not-a-secret<?php return [];",
            "<?= [];",
            "<?phpreturn [];",
            "<?php return [];;",
            "<?php return [",
            "<?php return ['a' => 'test-only-not-a-secret",
            "<?php return ['a' 'b'];",
            "<?php return ['a',, 'b'];",
            "<?php return [, 'a'];",
            "<?php return array('a' => 1];",
            "<?php return array 'a';",
            "<?php return [1.];",
            "<?php return [1e];",
            "<?php return [] /* test-only-not-a-secret",
            "<?php return []"})
    void rejectsMalformedSyntax(String text) {
        assertThatThrownBy(() -> parse(text)).as(text).isInstanceOf(LegacyConfigException.class)
                .satisfies(e -> assertThat(((LegacyConfigException) e).reason()).as(text)
                        .isIn(Reason.SYNTAX, Reason.UNSUPPORTED_CONSTRUCT))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(MARKER));
    }

    @Test
    void rejectsBomAndInvalidUtf8() {
        byte[] bom = "\uFEFF<?php return [];".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> VarExportParser.parse(bom)).isInstanceOf(LegacyConfigException.class)
                .extracting(e -> ((LegacyConfigException) e).reason()).isEqualTo(Reason.INVALID_ENCODING);
        for (byte[] bad : List.of(
                new byte[] {'<', '?', 'p', 'h', 'p', ' ', 'r', 'e', 't', 'u', 'r', 'n', ' ', '[', '\'', (byte) 0xC3, '\'', ']', ';'},
                new byte[] {'<', '?', 'p', 'h', 'p', ' ', 'r', 'e', 't', 'u', 'r', 'n', ' ', '[', '\'', (byte) 0xC0, (byte) 0xAF, '\'', ']', ';'},
                new byte[] {'<', '?', 'p', 'h', 'p', ' ', 'r', 'e', 't', 'u', 'r', 'n', ' ', '[', '\'', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '\'', ']', ';'})) {
            assertThatThrownBy(() -> VarExportParser.parse(bad)).isInstanceOf(LegacyConfigException.class)
                    .extracting(e -> ((LegacyConfigException) e).reason()).isEqualTo(Reason.INVALID_ENCODING);
        }
    }

    @Test
    void enforcesSizeDepthElementAndRangeLimits() {
        byte[] big = new byte[VarExportParser.MAX_BYTES + 1];
        assertThatThrownBy(() -> VarExportParser.parse(big)).isInstanceOf(LegacyConfigException.class)
                .extracting(e -> ((LegacyConfigException) e).reason()).isEqualTo(Reason.TOO_LARGE);

        String deepOk = "<?php return " + "[".repeat(VarExportParser.MAX_DEPTH) + "]".repeat(VarExportParser.MAX_DEPTH) + ";";
        assertThat(parse(deepOk).size()).isEqualTo(1);
        String tooDeep = "<?php return " + "[".repeat(VarExportParser.MAX_DEPTH + 1)
                + "]".repeat(VarExportParser.MAX_DEPTH + 1) + ";";
        rejects(tooDeep, Reason.LIMIT_EXCEEDED);

        String many = "<?php return [" + "0,".repeat(VarExportParser.MAX_ELEMENTS + 1) + "];";
        rejects(many, Reason.LIMIT_EXCEEDED);
        rejects("<?php return [9223372036854775808];", Reason.LIMIT_EXCEEDED);
        rejects("<?php return [-9223372036854775809];", Reason.LIMIT_EXCEEDED);
        rejects("<?php return [1.0E+999];", Reason.LIMIT_EXCEEDED);
        rejects("<?php return [9223372036854775807 => 'a', 'b'];", Reason.LIMIT_EXCEEDED);
    }

    @Test
    void errorsReportLineAndColumnButNotContent() {
        assertThatThrownBy(() -> parse("<?php\nreturn [\n  'k' => test_only_not_a_secret,\n];"))
                .hasMessageContaining("第 3 行第 10 列")
                .hasMessageNotContaining("test_only_not_a_secret");
    }

    @Test
    void valuesDoNotLeakContentThroughToString() {
        PhpArray a = parse("<?php return ['pass' => 'test-only-not-a-secret'];");
        assertThat(a.toString()).doesNotContain(MARKER);
        assertThat(a.get("pass").toString()).doesNotContain(MARKER);
    }
}

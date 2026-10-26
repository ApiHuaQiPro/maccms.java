package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 后台入参的旧语义（21 文档第 4.1 节）。filterExp 的结论由 PHP 黄金样本锁定：{@code login-pwd-like-filterexp}（库里 md5("like ")、
 * 提交 like → 登录成功）、{@code login-pwd-like-plain-stored}（库里 md5("like") → 失败）、{@code login-pwd-in-lower}、
 * {@code login-pwd-not-in}、{@code login-pwd-like-newline}（md5("like\n ")）、{@code login-pwd-not-keyword}、{@code login-name-filterexp}；
 * 这些用例由 AdminGoldenMySqlIT 经真实请求复放。
 */
class LegacyInputTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"like", "LIKE", "in", "NOT IN", "not in", "exp", "Null", "NOTBETWEEN TIME", "NOT BETWEEN TIME",
            "between time", "XOR", "egt", "neq", "notlike", "NOT EXISTS", "notnull"})
    void expressionKeywordsGetATrailingSpace(String in) {
        assertThat(LegacyInput.filterExp(b(in))).isEqualTo(b(in + " "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"likex", " like", "like ", "not  in", "NOTIN2", "in.", "", "0", "admin888", "非like", "NOT  NULL"})
    void otherValuesAreUntouched(String in) {
        assertThat(LegacyInput.filterExp(b(in))).isEqualTo(b(in));
    }

    @Test
    void dollarAlsoMatchesBeforeAFinalNewline() {
        // PCRE 的 $（无 D 修饰符）匹配末尾换行之前：旧版保存的是 md5("like\n ")（黄金 login-pwd-like-newline）
        assertThat(LegacyInput.filterExp(b("like\n"))).isEqualTo(b("like\n "));
        assertThat(LegacyInput.filterExp(b("like\n\n"))).isEqualTo(b("like\n\n"));
        assertThat(LegacyInput.filterExp(b("like\r\n"))).isEqualTo(b("like\r\n"));
        assertThat(LegacyInput.filterExp(null)).isNull();
    }

    @Test
    void md5IsOverRawBytesWithoutTrimming() {
        assertThat(LegacyInput.md5Hex(b("admin888"))).isEqualTo("7fef6171469e80d32c0559f88b377245");
        assertThat(LegacyInput.md5Hex(b("   "))).isEqualTo("628631f07321b22d8c176c200c855e1b");
        assertThat(LegacyInput.md5Hex(new byte[0])).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
        assertThat(LegacyInput.md5Hex(new byte[] {'a', 0, 'b'})).isEqualTo("70350f6027bce3713f6b76473084309b");
        assertThat(LegacyInput.md5Hex(new byte[] {(byte) 0xFF, 'a', 'b'})).hasSize(32);
    }

    @Test
    void phpEmptyIsOnlyEmptyStringAndZero() {
        assertThat(LegacyInput.empty(null)).isTrue();
        assertThat(LegacyInput.empty(b(""))).isTrue();
        assertThat(LegacyInput.empty(b("0"))).isTrue();
        assertThat(LegacyInput.empty(b("00"))).isFalse();
        assertThat(LegacyInput.empty(b(" "))).isFalse();
        assertThat(LegacyInput.empty(b("0.0"))).isFalse();
    }

    @Test
    void utf8BmpRejectsInvalidAndAstralText() {
        assertThat(LegacyInput.utf8Bmp(b("管理员 a"))).isEqualTo("管理员 a");
        assertThat(LegacyInput.utf8Bmp(new byte[] {(byte) 0xFF, 'a'})).isNull();
        assertThat(LegacyInput.utf8Bmp(b("ad😀"))).isNull();
    }

    @Test
    void htmlspecialcharsUsesEntCompatAndBlanksInvalidUtf8() {
        assertThat(LegacyInput.htmlspecialchars(b("a&b<c>\"d'e"))).isEqualTo("a&amp;b&lt;c&gt;&quot;d'e");
        assertThat(LegacyInput.htmlspecialchars(new byte[] {(byte) 0xC3})).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|0|true", "00|0|true", "0.0|0|true", " 0|0|true", "0 |0|true", "-0|0|true", "0e5|0|true",
            "1|1|true", "1.0|1|true", "01|1|true", "+1|1|true", "1e0|1|true",
            "abc|0|false", "''|0|false", "1abc|1|false", "2|1|false", "0x0|0|false"})
    void looseNumericEquality(String value, int target, boolean equal) {
        String v = value.equals("''") ? "" : value;
        assertThat(LegacyInput.looseEqualsNumber(v, target)).isEqualTo(equal);
    }
}

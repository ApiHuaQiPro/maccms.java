package com.maccms.backend.install.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.lang.LegacyLang;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ThinkPHP Validate 子集的边界（黄金样本覆盖了常见的不通过情况，这里补 PHP 语义的细节；31 文档第 3.4 节）。
 */
class InstallValidationTest {

    private static final LegacyLang CN = LegacyLang.of("zh-cn");
    private static final LegacyLang TW = LegacyLang.of("zh-tw");

    @Test
    void requireAcceptsZeroAndRejectsEmpty() {
        assertThat(InstallValidation.require("0", "t", CN)).isNull();
        assertThat(InstallValidation.require(" ", "t", CN)).isNull();
        assertThat(InstallValidation.require("", "t", CN)).isEqualTo("t不能为空");
        assertThat(InstallValidation.require(null, "t", TW)).isEqualTo("t require");
    }

    @Test
    void numberFollowsPhp7IsNumeric() {
        for (String ok : new String[] {"3306", " 3306", "+1", "-1", "1.5", ".5", "1e3", "1."}) {
            assertThat(InstallValidation.isNumeric(ok)).as(ok).isTrue();
        }
        for (String bad : new String[] {"abc", "0x1A", "1 ", "1e", "", "."}) {
            assertThat(InstallValidation.isNumeric(bad)).as(bad).isFalse();
        }
        assertThat(InstallValidation.number("", "t", CN)).isNull();
    }

    @Test
    void pcreDollarAcceptsOneTrailingNewline() {
        assertThat(InstallValidation.alphaNum("admin\n", "t", CN)).isNull();
        assertThat(InstallValidation.alphaNum("admin\n\n", "t", CN)).isEqualTo("t只能是字母和数字");
        assertThat(InstallValidation.regex("mac_\n", InstallWizard.PREFIX, InstallWizard.PREFIX_RULE, "t", CN)).isNull();
        assertThat(InstallValidation.regex("Mac_", InstallWizard.PREFIX, InstallWizard.PREFIX_RULE, "t", TW))
                .isEqualTo("t not conform to the rules");
    }

    @Test
    void lengthCountsCodePointsAndInIsLoose() {
        assertThat(InstallValidation.length("中文密码abc", 6, 20, "t", CN)).isNull();
        assertThat(InstallValidation.length("中文密码", 6, 20, "t", CN)).isEqualTo("t长度不符合要求 6,20");
        assertThat(InstallValidation.in("00", new String[] {"0", "1"}, "t", CN)).isNull();
        assertThat(InstallValidation.in("2", new String[] {"0", "1"}, "t", CN)).isEqualTo("t必须在 0,1 范围内");
        assertThat(InstallValidation.truthy("00")).isTrue();
        assertThat(InstallValidation.truthy("0")).isFalse();
    }

    @Test
    void connectionRulesStopAtTheFirstFailureInLegacyOrder() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("hostport", "abc");
        d.put("prefix", "X");
        assertThat(InstallWizard.validateConnection(d, CN)).isEqualTo("服务器地址不能为空");
        d.put("hostname", "h");
        assertThat(InstallWizard.validateConnection(d, CN)).isEqualTo("数据库端口必须是数字");
    }

    @Test
    void siteConfigSetKeepsOrderAndAppendsMissingKeys() {
        var a = new com.maccms.backend.legacyconfig.application.view.PhpArray(Map.of());
        var b = InstallWizard.set(a, new com.maccms.backend.legacyconfig.application.view.PhpValue.PhpInt(0), "api", "vod", "status");
        assertThat(b.path("api", "vod", "status")).isEqualTo(new com.maccms.backend.legacyconfig.application.view.PhpValue.PhpInt(0));
        assertThat(InstallWizard.md5("Test-only-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("659b3bbe7a19dbf14df36fe087e7d90e");
    }
}

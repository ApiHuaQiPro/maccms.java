package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B10 的 PHP 数值语义与财务配置读取。期望值取自本机 PHP 7.3.33 / 8.0.30（{@code precision=14}）的实际输出，两版相同处才列出；
 * 金额、提现与删除的整体行为由 {@code MemberFinanceGoldenMySqlIT} 对照黄金。
 */
class MemberFinanceUnitTest {

    @Test
    void floatToStringMatchesPhpPrecision14() {
        Map<Double, String> php = new LinkedHashMap<>();
        php.put(1e14, "1.0E+14");
        php.put(99999999999999.0, "99999999999999");
        php.put(999999999999999.0, "1.0E+15");
        php.put(0.0001, "0.0001");
        php.put(0.00001, "1.0E-5");
        php.put(-0.0, "-0");
        php.put(0.1 + 0.2, "0.3");
        php.put(12.5, "12.5");
        php.put(-3.0, "-3");
        php.put(1.2345678901234567, "1.2345678901235");
        php.put(123456789012345.0, "1.2345678901234E+14");
        php.forEach((d, s) -> assertThat(PhpNumbers.phpString(d)).as(Double.toString(d)).isEqualTo(s));
        assertThat(PhpNumbers.phpString(Double.POSITIVE_INFINITY)).isEqualTo("INF");
    }

    @Test
    void numericPrefixesFollowPhp() {
        assertThat(PhpNumbers.isNumeric(" 1")).isTrue();
        assertThat(PhpNumbers.isNumeric("+.5e3")).isTrue();
        for (String s : new String[] {"1e", ".", "0x1A", "", "abc"}) {
            assertThat(PhpNumbers.isNumeric(s)).as(s).isFalse();
        }
        assertThat(PhpNumbers.floatval("  2.5abc")).isEqualTo(2.5);
        assertThat(PhpNumbers.floatval("abc")).isZero();
        assertThat(PhpNumbers.intval(-3.7)).isEqualTo(-3);
        assertThat(PhpNumbers.intval(Double.NaN)).isZero();
        // PHP 7.3：intval(abs(" -3.7x")) = 3；PHP 8 对非数值串抛类型错误（D-B10-8，按 7.3）
        assertThat(PhpNumbers.absIntval(" -3.7x")).isEqualTo(3);
        assertThat(PhpNumbers.absIntval("x")).isZero();
        assertThat(PhpNumbers.absIntval("42")).isEqualTo(42);
    }

    @Test
    void settingsReadLegacyConfigWithEmptyMeaningNoReward() {
        assertThat(MemberFinanceSettings.from(null)).isSameAs(MemberFinanceSettings.REPOSITORY);
        Map<Object, PhpValue> user = new LinkedHashMap<>();
        user.put("invite_reg_points", new PhpValue.PhpString("7"));
        user.put("invite_visit_points", new PhpValue.PhpString("2"));
        user.put("invite_visit_num", new PhpValue.PhpString(""));
        user.put("reward_status", new PhpValue.PhpString("1"));
        user.put("reward_ratio", new PhpValue.PhpString("12.5"));
        user.put("reward_ratio_2", new PhpValue.PhpString("0"));
        user.put("reward_ratio_3", new PhpValue.PhpString(""));
        user.put("cash_status", new PhpValue.PhpString("0"));
        user.put("cash_ratio", new PhpValue.PhpString("100"));
        user.put("cash_min", new PhpValue.PhpString("0.5元"));
        Map<Object, PhpValue> card = new LinkedHashMap<>();
        card.put("url", new PhpValue.PhpString("https://shop.example.invalid/"));
        Map<Object, PhpValue> pay = new LinkedHashMap<>();
        pay.put("min", new PhpValue.PhpFloat(2.5));
        pay.put("scale", new PhpValue.PhpInt(10));
        pay.put("card", new PhpArray(card));
        Map<Object, PhpValue> root = new LinkedHashMap<>();
        root.put("user", new PhpArray(user));
        root.put("pay", new PhpArray(pay));
        MemberFinanceSettings s = MemberFinanceSettings.from(new PhpArray(root));
        assertThat(s.inviteRegPoints()).isEqualTo(7);
        assertThat(s.inviteVisitPoints()).isEqualTo(2);
        assertThat(s.inviteVisitMax()).as("空 invite_visit_num 按 1（User.php visit）").isEqualTo(1);
        assertThat(s.rewardOn()).isTrue();
        assertThat(s.rewardRatios()).containsExactly(12.5, null, null);
        assertThat(s.cashOn()).isFalse();
        assertThat(s.cashMin()).isEqualTo(0.5);
        assertThat(s.cashMinText()).isEqualTo("0.5元");
        assertThat(s.payMin()).isEqualTo(2.5);
        assertThat(s.payMinText()).isEqualTo("2.5");
        assertThat(s.payScale()).isEqualTo(10);
        assertThat(s.cardShopUrl()).isEqualTo("https://shop.example.invalid/");
    }
}

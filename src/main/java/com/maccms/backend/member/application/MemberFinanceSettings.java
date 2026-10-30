package com.maccms.backend.member.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;

/**
 * 会员权益与财务用到的旧配置（B10，docs/architecture/19-b10-member-finance.md 第 7 节）：{@code extra/maccms.php} 的 {@code user}
 * 与 {@code pay} 段，启动时读一次；没有旧配置目录时用仓库值（{@code application/extra/maccms.php:99-140}）。取值规则按旧代码的写法：
 * {@code == '1'} 为 PHP 8 宽松比较，{@code intval}、{@code empty()}、算术中的数值串按 PHP。
 *
 * @param inviteRegPoints   {@code intval(user.invite_reg_points)}：推广注册给邀请人的积分（{@code common/model/User.php:252-263}）
 * @param inviteVisitPoints {@code intval(user.invite_visit_points)}：推广访问的积分（{@code :877-889}）
 * @param inviteVisitMax    {@code user.invite_visit_num}，{@code empty()} 时为 1（{@code :858-861}）
 * @param rewardOn          {@code user.reward_status == '1'}（{@code :897}）
 * @param rewardRatios      {@code user.reward_ratio}、{@code _2}、{@code _3} 参与算术的数值；{@code empty()} 的级别为 null（不发放）
 * @param cashOn            {@code user.cash_status == '1'}（{@code common/model/Cash.php:71}）
 * @param cashRatio         {@code user.cash_ratio} 的数值（每元积分，{@code :79}）
 * @param cashMin           {@code user.cash_min} 的数值（{@code :75}）
 * @param cashMinText       {@code user.cash_min} 原文（提示语）
 * @param payMin            {@code pay.min} 的数值（{@code index/controller/User.php:449}）
 * @param payMinText        {@code pay.min} 原文（提示语）
 * @param payScale          {@code pay.scale} 的数值（每元积分，{@code :458}）
 * @param cardShopUrl       {@code pay.card.url} 原文（充值页"购买充值卡"链接，第 7 节）
 */
public record MemberFinanceSettings(long inviteRegPoints, long inviteVisitPoints, long inviteVisitMax, boolean rewardOn,
                                    Double[] rewardRatios, boolean cashOn, double cashRatio, double cashMin,
                                    String cashMinText, double payMin, String payMinText, double payScale,
                                    String cardShopUrl) {

    /** 仓库值（{@code application/extra/maccms.php:108-120、131-141}）。 */
    public static final MemberFinanceSettings REPOSITORY = new MemberFinanceSettings(10, 1, 1, true,
            new Double[] {10.0, 30.0, 50.0}, true, 100, 1, "1", 10, "10", 1, "");

    public MemberFinanceSettings {
        if (rewardRatios == null || rewardRatios.length != 3) {
            throw new IllegalArgumentException("rewardRatios 须有 3 级");
        }
        rewardRatios = rewardRatios.clone();
        cashMinText = cashMinText == null ? "" : cashMinText;
        payMinText = payMinText == null ? "" : payMinText;
        cardShopUrl = cardShopUrl == null ? "" : cardShopUrl;
    }

    @Override
    public Double[] rewardRatios() {
        return rewardRatios.clone();
    }

    /** 第 {@code level}（1～3）级的分销比例；不发放时为 null。 */
    public Double rewardRatio(int level) {
        return rewardRatios[level - 1];
    }

    /** @param maccms 旧配置 {@code extra/maccms.php}，没有配置旧配置目录时为 null */
    public static MemberFinanceSettings from(PhpArray maccms) {
        if (maccms == null) {
            return REPOSITORY;
        }
        PhpValue visitNum = maccms.path("user", "invite_visit_num");
        return new MemberFinanceSettings(
                MemberSettings.intval(maccms.path("user", "invite_reg_points")),
                MemberSettings.intval(maccms.path("user", "invite_visit_points")),
                empty(visitNum) ? 1 : MemberSettings.intval(visitNum),
                MemberSettings.equalsInt(maccms.path("user", "reward_status"), 1),
                new Double[] {ratio(maccms.path("user", "reward_ratio")), ratio(maccms.path("user", "reward_ratio_2")),
                        ratio(maccms.path("user", "reward_ratio_3"))},
                MemberSettings.equalsInt(maccms.path("user", "cash_status"), 1),
                number(maccms.path("user", "cash_ratio")),
                number(maccms.path("user", "cash_min")),
                text(maccms.path("user", "cash_min")),
                number(maccms.path("pay", "min")),
                text(maccms.path("pay", "min")),
                number(maccms.path("pay", "scale")),
                text(maccms.path("pay", "card", "url")));
    }

    private static Double ratio(PhpValue v) {
        return empty(v) ? null : number(v);
    }

    /** PHP {@code empty()}：null、空串、{@code "0"}、0、false。 */
    static boolean empty(PhpValue v) {
        if (v == null || v instanceof PhpValue.PhpNull) {
            return true;
        }
        if (v instanceof PhpValue.PhpString s) {
            return s.value().isEmpty() || s.value().equals("0");
        }
        if (v instanceof PhpValue.PhpInt i) {
            return i.value() == 0;
        }
        if (v instanceof PhpValue.PhpFloat f) {
            return f.value() == 0;
        }
        if (v instanceof PhpValue.PhpBool b) {
            return !b.value();
        }
        return v instanceof PhpArray a && a.size() == 0;
    }

    /** 算术中的数值（数值串的数字前缀；非数值为 0）。 */
    static double number(PhpValue v) {
        if (v instanceof PhpValue.PhpInt i) {
            return i.value();
        }
        if (v instanceof PhpValue.PhpFloat f) {
            return f.value();
        }
        if (v instanceof PhpValue.PhpBool b) {
            return b.value() ? 1 : 0;
        }
        if (v instanceof PhpValue.PhpString s) {
            return PhpNumbers.floatval(s.value());
        }
        return 0;
    }

    private static String text(PhpValue v) {
        if (v instanceof PhpValue.PhpString s) {
            return s.value();
        }
        if (v instanceof PhpValue.PhpInt i) {
            return Long.toString(i.value());
        }
        if (v instanceof PhpValue.PhpFloat f) {
            return PhpNumbers.phpString(f.value());
        }
        return "";
    }
}

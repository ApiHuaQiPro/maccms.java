package com.maccms.backend.member.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 会员账号用到的旧配置 {@code extra/maccms.php} 的 {@code user} 段（B09，18 文档第 7 节），启动时读一次。没有旧配置目录时用仓库值
 * （{@code application/extra/maccms.php:99-130}）。字段按旧代码的比较写法保存：{@code == 1}/{@code == 0} 用 PHP 8 的宽松比较（数值串按
 * 数值，非数值串不等于任何整数），{@code intval} 按 PHP。
 *
 * @param statusZero     {@code user.status == 0}（旧 {@code User.php:133}）
 * @param regOpenZero    {@code user.reg_open == 0}
 * @param regStatus      {@code intval(user.reg_status)}：新会员的 {@code user_status}（{@code :183}）
 * @param regVerify      {@code user.reg_verify == 1}（{@code :139}）
 * @param loginVerify    {@code user.login_verify == 1}（{@code :335}）
 * @param regPoints      {@code intval(user.reg_points)}（{@code :182}）
 * @param regNum         {@code intval(user.reg_num)}（&gt; 0 时每 IP 每天限额，{@code :168}）
 * @param regNumText     {@code user.reg_num} 原文（提示语按 {@code %s} 代入，{@code :174}）
 * @param filterWords    {@code user.filter_words} 原文（逗号分隔，{@code :158}）
 * @param regPhoneSms    {@code user.reg_phone_sms == '1'}（{@code :191}）
 * @param regEmailSms    {@code user.reg_email_sms == '1'}（{@code :210}）
 * @param emailWhiteHosts {@code user.email_white_hosts} 原文（{@code common/validate/User.php:44-55}）
 * @param emailBlackHosts {@code user.email_black_hosts} 原文
 * @param portraitZero   {@code user.portrait_status == 0}（{@code index/controller/User.php:387}）
 * @param portraitWidth  {@code user.portrait_size} 的宽，1～1000；解析不了时为 0（头像功能按关闭）
 * @param portraitHeight 同上的高；只给一个数时与宽相同
 */
public record MemberSettings(boolean statusZero, boolean regOpenZero, int regStatus, boolean regVerify,
                             boolean loginVerify, long regPoints, long regNum, String regNumText, String filterWords,
                             boolean regPhoneSms, boolean regEmailSms, String emailWhiteHosts, String emailBlackHosts,
                             boolean portraitZero, int portraitWidth, int portraitHeight) {

    /** 仓库值（{@code application/extra/maccms.php:99-130}）。 */
    public static final MemberSettings REPOSITORY = new MemberSettings(false, false, 1, false, false, 10, 1, "1",
            "admin,cao,sex,xxx", false, false, "", "", false, 100, 100);

    private static final Pattern PHP8_NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\x0B\\f]*");

    public MemberSettings {
        regNumText = regNumText == null ? "" : regNumText;
        filterWords = filterWords == null ? "" : filterWords;
        emailWhiteHosts = emailWhiteHosts == null ? "" : emailWhiteHosts;
        emailBlackHosts = emailBlackHosts == null ? "" : emailBlackHosts;
    }

    /** @param maccms 旧配置 {@code extra/maccms.php}，没有配置旧配置目录时为 null */
    public static MemberSettings from(PhpArray maccms) {
        if (maccms == null) {
            return REPOSITORY;
        }
        int[] size = portraitSize(text(maccms.path("user", "portrait_size")));
        long regStatus = intval(maccms.path("user", "reg_status"));
        return new MemberSettings(
                equalsInt(maccms.path("user", "status"), 0),
                equalsInt(maccms.path("user", "reg_open"), 0),
                (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, regStatus)),
                equalsInt(maccms.path("user", "reg_verify"), 1),
                equalsInt(maccms.path("user", "login_verify"), 1),
                intval(maccms.path("user", "reg_points")),
                intval(maccms.path("user", "reg_num")),
                text(maccms.path("user", "reg_num")),
                text(maccms.path("user", "filter_words")),
                equalsInt(maccms.path("user", "reg_phone_sms"), 1),
                equalsInt(maccms.path("user", "reg_email_sms"), 1),
                text(maccms.path("user", "email_white_hosts")),
                text(maccms.path("user", "email_black_hosts")),
                equalsInt(maccms.path("user", "portrait_status"), 0),
                size[0], size[1]);
    }

    /** 旧 {@code $config['user']['status'] == 0 || $config['user']['reg_open'] == 0}。 */
    public boolean registrationClosed() {
        return statusZero || regOpenZero;
    }

    /**
     * 旧 {@code explode('x', strtolower(portrait_size))}，只给一个数时为正方形（{@code Upload.php:223-227}）。两项都须是 1～1000 的
     * 十进制整数（旧版交给 GD，非法值会出错；新版限制范围防止超大图片），否则返回 {0, 0}。
     */
    static int[] portraitSize(String raw) {
        if (raw == null) {
            return new int[] {0, 0};
        }
        String[] parts = raw.toLowerCase(Locale.ROOT).split("x", -1);
        if (parts.length > 2) {
            return new int[] {0, 0};
        }
        int w = dimension(parts[0]);
        int h = parts.length == 2 ? dimension(parts[1]) : w;
        return w > 0 && h > 0 ? new int[] {w, h} : new int[] {0, 0};
    }

    private static int dimension(String s) {
        String t = s.strip();
        if (!t.matches("[0-9]{1,4}")) {
            return 0;
        }
        int v = Integer.parseInt(t);
        return v >= 1 && v <= 1000 ? v : 0;
    }

    /** PHP 8 {@code $v == $n}（$n 为整数）。 */
    static boolean equalsInt(PhpValue v, long n) {
        if (v == null || v instanceof PhpValue.PhpNull) {
            return n == 0;
        }
        if (v instanceof PhpValue.PhpInt i) {
            return i.value() == n;
        }
        if (v instanceof PhpValue.PhpBool b) {
            return b.value() == (n != 0);
        }
        if (v instanceof PhpValue.PhpFloat f) {
            return f.value() == n;
        }
        if (v instanceof PhpValue.PhpString s) {
            if (!PHP8_NUMERIC.matcher(s.value()).matches()) {
                return false;
            }
            try {
                return new BigDecimal(s.value().strip()).compareTo(BigDecimal.valueOf(n)) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** PHP {@code intval}：字符串按 {@link PhpIntval}；其他类型按 PHP。 */
    static long intval(PhpValue v) {
        if (v == null || v instanceof PhpValue.PhpNull) {
            return 0;
        }
        if (v instanceof PhpValue.PhpInt i) {
            return i.value();
        }
        if (v instanceof PhpValue.PhpBool b) {
            return b.value() ? 1 : 0;
        }
        if (v instanceof PhpValue.PhpFloat f) {
            double d = f.value();
            return Double.isFinite(d) ? (long) d : 0;
        }
        if (v instanceof PhpValue.PhpString s) {
            return PhpIntval.of(s.value());
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
        return "";
    }
}

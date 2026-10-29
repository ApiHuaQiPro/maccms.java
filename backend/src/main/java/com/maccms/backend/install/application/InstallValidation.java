package com.maccms.backend.install.application;

import com.maccms.backend.common.lang.LegacyLang;
import java.util.regex.Pattern;

/**
 * 安装向导用到的 ThinkPHP 5.0 {@code Validate} 规则子集（thinkphp/library/think/Validate.php，B22 31 文档第 3.4 节）：
 * {@code require}、{@code number}、{@code alphaNum}、{@code length:a,b}、{@code regex:…}、{@code in:…}，语义与旧版逐条对应——
 * 除 {@code require} 外，值为 null 或空串时不检查（{@code checkItem}，:404-406）；第一条不通过即停（不批量）。
 *
 * <p>提示文字（{@code getRuleMsg}，:1300-1335）：旧版先取规则的英文提示，再到当前语言包里找译文。框架语言包只有
 * {@code thinkphp/lang/zh-cn.php}，安装模块切到繁体（{@code Lang::range('zh-tw')}）后找不到译文，显示的就是英文原文；这里按同样的两种情况输出。
 */
final class InstallValidation {

    private InstallValidation() {
    }

    /** 一条规则不通过时的提示；通过时返回 null。 */
    static String require(String value, String title, LegacyLang lang) {
        // 旧 :585-587：!empty($value) || '0' == $value
        return value != null && !value.isEmpty() ? null : message(lang, ":attribute require", ":attribute不能为空", title, "");
    }

    static String number(String value, String title, LegacyLang lang) {
        if (value == null || value.isEmpty() || isNumeric(value)) {
            return null;
        }
        return message(lang, ":attribute must be numeric", ":attribute必须是数字", title, "");
    }

    static String alphaNum(String value, String title, LegacyLang lang) {
        if (value == null || value.isEmpty() || pregDollar(ALPHA_NUM, value)) {
            return null;
        }
        return message(lang, ":attribute must be alpha-numeric", ":attribute只能是字母和数字", title, "");
    }

    /** {@code length:min,max}：按 {@code mb_strlen}（码点数）。 */
    static String length(String value, int min, int max, String title, LegacyLang lang) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        int n = value.codePointCount(0, value.length());
        if (n >= min && n <= max) {
            return null;
        }
        return message(lang, "size of :attribute must be :rule", ":attribute长度不符合要求 :rule", title, min + "," + max);
    }

    /** {@code regex:…}：旧版给规则两端补 {@code /^…$/}，PCRE 的 {@code $} 也接受末尾一个换行。 */
    static String regex(String value, Pattern body, String rule, String title, LegacyLang lang) {
        if (value == null || value.isEmpty() || pregDollar(body, value)) {
            return null;
        }
        return message(lang, ":attribute not conform to the rules", ":attribute不符合指定规则", title, rule);
    }

    /** {@code in:a,b}：PHP {@code in_array} 的宽松比较（两边都是数字串时按数值比较）。 */
    static String in(String value, String[] allowed, String title, LegacyLang lang) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (String a : allowed) {
            if (looseEquals(value, a)) {
                return null;
            }
        }
        return message(lang, ":attribute must be in :rule", ":attribute必须在 :rule 范围内", title, String.join(",", allowed));
    }

    /** PHP 的 {@code if ($v)}：只有空串与 "0" 为假。 */
    static boolean truthy(String value) {
        return value != null && !value.isEmpty() && !value.equals("0");
    }

    private static final Pattern ALPHA_NUM = Pattern.compile("[A-Za-z0-9]+");
    /** PHP 7 {@code is_numeric}：前导空白、可选符号、十进制数（可带小数点与指数）；不接受末尾空白与十六进制。 */
    private static final Pattern NUMERIC = Pattern.compile("[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    static boolean isNumeric(String s) {
        return NUMERIC.matcher(s).matches();
    }

    /** {@code preg_match('/^…$/', $v)}：{@code $} 在末尾的一个 \n 之前也成立。 */
    private static boolean pregDollar(Pattern body, String value) {
        return body.matcher(value).matches()
                || (value.endsWith("\n") && body.matcher(value.substring(0, value.length() - 1)).matches());
    }

    static boolean looseEquals(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (isNumeric(a) && isNumeric(b)) {
            try {
                return new java.math.BigDecimal(a.strip()).compareTo(new java.math.BigDecimal(b.strip())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 旧 {@code getRuleMsg}：zh-cn 有框架译文，其他语言显示英文原文；随后把 {@code :attribute}、{@code :rule} 换成标题与规则。
     */
    private static String message(LegacyLang lang, String english, String zhCn, String title, String rule) {
        String msg = LegacyLang.DEFAULT_CODE.equals(lang.code()) ? zhCn : english;
        return msg.replace(":attribute", title).replace(":rule", rule);
    }
}

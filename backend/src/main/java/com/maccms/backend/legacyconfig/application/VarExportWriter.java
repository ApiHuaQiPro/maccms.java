package com.maccms.backend.legacyconfig.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * PHP {@code var_export($v, true)} 的逐字节等价输出（B15，docs/architecture/24-b15-admin-system.md 第 3 节），以及旧版写配置文件的
 * 三种外壳：
 * <ul>
 * <li>{@link #macArr2File}：{@code mac_arr2file}（application/common.php:126-139）写出的 {@code "<?php\nreturn " . 值 . ";"}，
 * 用于 {@code extra/maccms.php}、{@code route.php}、{@code extra/domain.php}、{@code extra/vodplayer.php} 等；</li>
 * <li>{@link #addonsFile}：fastadmin-addons {@code Service::refresh} 写 {@code extra/addons.php} 的 {@code "<?php\n\nreturn " . 值 . ";"}；</li>
 * <li>{@link #addonConfigFile}：{@code set_addon_fullconfig} 写插件 {@code config.php} 的 {@code "<?php\n\nreturn " . 值 . ";\n"}。</li>
 * </ul>
 *
 * <p>格式（PHP 7.3、8.0 的 {@code ext/standard/var.c} {@code php_var_export_ex}，本机两版实测相同，黄金
 * {@code legacyconfig/var-export-golden.json}）：数组为 {@code array (} 换行、每个元素一行（缩进为层级 × 2 + 2 个空格，
 * {@code 键 => 值,}）、{@code )}；嵌套数组的值另起一行并缩进；字符串用单引号，只转义 {@code \} 与 {@code '}；整数十进制；
 * 浮点按 {@code serialize_precision=-1}（最短往返表示，{@code php_gcvt} 的写法，整数值补 {@code .0}）；{@code true}/{@code false}/{@code NULL}。
 *
 * <p>不支持（抛 {@link IllegalArgumentException}，调用方按"保存失败"处理，不写文件）：含 NUL 字节的字符串（PHP 写成
 * {@code '' . "\0" . ''}，{@link VarExportParser} 不接受这种拼接，写出去新版自己就读不回来）、{@code PHP_INT_MIN}（同理）、
 * NaN 与无穷大（PHP 写成 {@code NAN}/{@code INF} 常量）。
 */
public final class VarExportWriter {

    private VarExportWriter() {
    }

    /** {@code mac_arr2file} 写出的文件内容（UTF-8）。 */
    public static byte[] macArr2File(PhpArray value) {
        return ("<?php\nreturn " + export(value) + ";").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code Service::refresh} 写出的 {@code extra/addons.php}。 */
    public static byte[] addonsFile(PhpArray value) {
        return ("<?php\n\nreturn " + export(value) + ";").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code set_addon_fullconfig} 写出的插件 {@code config.php}。 */
    public static byte[] addonConfigFile(PhpArray value) {
        return ("<?php\n\nreturn " + export(value) + ";\n").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code var_export($value, true)}。 */
    public static String export(PhpValue value) {
        StringBuilder sb = new StringBuilder();
        export(sb, value, 1);
        return sb.toString();
    }

    private static void export(StringBuilder sb, PhpValue value, int level) {
        switch (value) {
            case PhpValue.PhpString s -> string(sb, s.value());
            case PhpValue.PhpInt i -> {
                if (i.value() == Long.MIN_VALUE) {
                    throw new IllegalArgumentException("PHP_INT_MIN 不能写成 var_export 数据");
                }
                sb.append(i.value());
            }
            case PhpValue.PhpFloat f -> sb.append(floatText(f.value()));
            case PhpValue.PhpBool b -> sb.append(b.value() ? "true" : "false");
            case PhpValue.PhpNull n -> sb.append("NULL");
            case PhpArray a -> {
                if (level > 1) {
                    sb.append('\n');
                    spaces(sb, level - 1);
                }
                sb.append("array (\n");
                for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
                    spaces(sb, level + 1);
                    if (e.getKey() instanceof Long k) {
                        sb.append(k.longValue());
                    } else {
                        string(sb, (String) e.getKey());
                    }
                    sb.append(" => ");
                    export(sb, e.getValue(), level + 2);
                    sb.append(",\n");
                }
                if (level > 1) {
                    spaces(sb, level - 1);
                }
                sb.append(')');
            }
        }
    }

    private static void spaces(StringBuilder sb, int n) {
        sb.append(" ".repeat(n));
    }

    private static void string(StringBuilder sb, String s) {
        if (s.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("字符串含 NUL 字节");
        }
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('\'');
    }

    /**
     * PHP 7.1+ {@code var_export} 的浮点：{@code php_gcvt(value, 17, '.', 'E')}，{@code serialize_precision=-1} 时取最短往返的有效数字
     * （{@code zend_dtoa} mode 0）；小数点位置 {@code decpt}（值 = 0.数字 × 10^decpt）小于 -3 或大于 17 时写成科学计数法
     * {@code d.dddE+x}（尾数至少一位小数），否则写成普通小数；结果里没有小数点时补 {@code .0}。
     */
    static String floatText(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("NAN/INF 不能写成 var_export 数据");
        }
        boolean negative = (Double.doubleToRawLongBits(d) & Long.MIN_VALUE) != 0;
        String digits;
        int decpt;
        if (d == 0.0) {
            digits = "0";
            decpt = 1;
        } else {
            // 最短往返的有效数字：把精确值依次舍入到 1..17 位，取第一个能还原出同一个 double 的（Double.toString 至少给两位，
            // 如 Double.MIN_VALUE 是 4.9E-324，而 zend_dtoa 给 5）
            BigDecimal exact = new BigDecimal(Math.abs(d));
            BigDecimal bd = null;
            for (int p = 1; p <= 17; p++) {
                BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
                if (r.doubleValue() == Math.abs(d)) {
                    bd = r;
                    break;
                }
            }
            bd = (bd == null ? exact.round(new MathContext(17, RoundingMode.HALF_EVEN)) : bd).stripTrailingZeros();
            digits = bd.unscaledValue().toString();
            decpt = digits.length() - bd.scale();
        }
        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        if (decpt < 0 ? decpt < -3 : decpt > 17) {
            int exp = decpt - 1;
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() > 1 ? digits.substring(1) : "0");
            out.append('E').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
            return out.toString();
        }
        if (decpt < 0) {
            out.append("0.").append("0".repeat(-decpt)).append(digits);
        } else {
            for (int i = 0; i < decpt; i++) {
                out.append(i < digits.length() ? digits.charAt(i) : '0');
            }
            if (digits.length() > decpt) {
                if (decpt == 0) {
                    out.append('0');
                }
                out.append('.').append(digits, decpt, digits.length());
            }
        }
        if (out.indexOf(".") < 0) {
            out.append(".0");
        }
        return out.toString();
    }
}

package com.maccms.backend.content.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** PHP 的数值取整与输出（B13 附件大小、上传大小）。 */
public final class PhpNumbers {

    private PhpNumbers() {
    }

    /** PHP {@code round($v, 2)}（四舍五入，远离零）。 */
    public static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** PHP 把浮点数转成文字（{@code echo}，precision=14）：整数值不带小数点，其余去掉末尾的零。 */
    public static String text(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new BigDecimal(v).round(new java.math.MathContext(14, RoundingMode.HALF_EVEN)).stripTrailingZeros()
                .toPlainString();
    }

    /** 两步合起来：{@code round($v, 2)} 的输出文字。 */
    public static String round2Text(double v) {
        return text(round2(v));
    }
}

package com.maccms.backend.common.captcha;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 验证码图片参数（B08，17 文档第 2.4 节）：默认值与 think-captcha（{@code vendor/topthink/think-captcha/src/Captcha.php} 的
 * {@code $config}）相同，再由旧 {@code application/extra/captcha.php} 覆盖（仓库值：{@code codeSet=1234567890}、{@code length=4}、
 * {@code fontSize=16}、{@code useNoise=false}、{@code useCurve=false}）。宽高为 0 时按旧公式计算：
 * 宽 = {@code length × fontSize × 1.5 + length × fontSize / 2}，高 = {@code fontSize × 2.5}（取整数部分）。
 *
 * <p>校验用的参数（有效期 1800 秒、通过后作废）不在这里：旧 {@code captcha_check()} 不传配置，总是用 think-captcha 的默认值
 * （见 {@link Captchas}）。{@code seKey}、{@code expire}、{@code reset}、{@code fontttf}、{@code useImgBg}、{@code bg} 以外的
 * 未知键忽略。
 *
 * <p>中文验证码：{@code useZh} 为真时码字从 {@code zhSet} 按字（码位）均匀抽取（旧 {@code iconv_substr(zhSet, mt_rand(0,
 * mb_strlen−1), 1)}），{@code codeSet} 不用；{@code zhSet} 默认是 think-captcha 自带的 1500 字码表，旧配置可以覆盖。字形由
 * {@link ZhGlyphs} 内置，所以 {@code zhSet} 里的字都必须有内置字形（GB2312 一、二级汉字）。
 */
public record CaptchaSettings(String codeSet, int length, double fontSize, boolean useNoise, boolean useCurve,
                              int imageW, int imageH, int[] bg, boolean useZh, String zhSet) {

    public static final CaptchaSettings THINK_DEFAULTS = new CaptchaSettings(
            "2345678abcdefhijkmnpqrstuvwxyzABCDEFGHJKLMNPQRTUVWXY", 5, 25, true, true, 0, 0,
            new int[] {243, 251, 254}, false, ZhGlyphs.thinkZhSet());

    /** 允许的码长与字号（防止配置失误生成超大图片）。 */
    static final int MAX_LENGTH = 16;
    static final double MAX_FONT_SIZE = 100;

    public CaptchaSettings {
        bg = bg.clone();
    }

    @Override
    public int[] bg() {
        return bg.clone();
    }

    /** 实际图片宽度。 */
    public int width() {
        return imageW > 0 ? imageW : (int) (length * fontSize * 1.5 + length * fontSize / 2);
    }

    /** 实际图片高度。 */
    public int height() {
        return imageH > 0 ? imageH : (int) (fontSize * 2.5);
    }

    /**
     * 以 think-captcha 默认值为底，按旧 {@code captcha.php} 的键覆盖。值的类型是 PHP {@code var_export} 能产生的
     * {@code String}、{@code Long}、{@code Double}、{@code Boolean} 或 null（调用方从旧配置读取后转换）。
     * 新版不支持的取值（英文码表含非 ASCII 可见字符、中文码表含没有内置字形的字、码长或字号越界）抛
     * {@link IllegalStateException}，信息只含键名。
     */
    public static CaptchaSettings fromLegacy(Map<String, Object> legacy) {
        CaptchaSettings d = THINK_DEFAULTS;
        String codeSet = d.codeSet;
        int length = d.length;
        double fontSize = d.fontSize;
        boolean noise = d.useNoise;
        boolean curve = d.useCurve;
        int w = d.imageW;
        int h = d.imageH;
        int[] bg = d.bg;
        boolean zh = d.useZh;
        String zhSet = d.zhSet;
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, Object> e : legacy.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "codeSet" -> codeSet = v == null ? "" : String.valueOf(v);
                case "length" -> length = (int) number(v);
                case "fontSize" -> fontSize = number(v);
                case "useNoise" -> noise = truthy(v);
                case "useCurve" -> curve = truthy(v);
                case "imageW" -> w = (int) number(v);
                case "imageH" -> h = (int) number(v);
                case "useZh" -> zh = truthy(v);
                case "zhSet" -> zhSet = v == null ? "" : String.valueOf(v);
                default -> {
                    // seKey/expire/reset/fontttf/useImgBg/bg 等：校验参数固定为默认值，字体与背景图由新版自带的笔画字形代替
                }
            }
        }
        if (zh) {
            if (zhSet.isEmpty() || !zhSet.codePoints().allMatch(ZhGlyphs::has)) {
                errors.add("zhSet（只支持 GB2312 一、二级汉字）");
            }
        } else if (codeSet.isEmpty() || !codeSet.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            errors.add("codeSet（只支持 ASCII 可见字符）");
        }
        if (length < 1 || length > MAX_LENGTH) {
            errors.add("length（1～" + MAX_LENGTH + "）");
        }
        if (!(fontSize >= 4 && fontSize <= MAX_FONT_SIZE)) {
            errors.add("fontSize（4～" + (int) MAX_FONT_SIZE + "）");
        }
        if (w < 0 || w > 2000 || h < 0 || h > 1000) {
            errors.add("imageW/imageH");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("旧验证码配置 extra/captcha.php 不受支持：" + String.join("；", errors));
        }
        return new CaptchaSettings(codeSet, length, fontSize, noise, curve, w, h, bg, zh, zhSet);
    }

    /** PHP 的数值转换（数字字符串取数值，其余为 0）。 */
    private static double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** PHP 的真值（false、0、""、"0"、null 为假）。 */
    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        String s = String.valueOf(v);
        return !s.isEmpty() && !s.equals("0");
    }
}

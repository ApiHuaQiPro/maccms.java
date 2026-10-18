package com.maccms.backend.common.captcha;

import com.maccms.backend.common.session.SessionState;
import java.util.random.RandomGenerator;

/**
 * 本地验证码的签发与校验（B08，17 文档第 2.4 节），对应 think-captcha 的 {@code Captcha::entry} 与 {@code captcha_check()}。
 * 验证码记录放在进程内会话（{@link SessionState}），按 {@code id} 分槽（旧版 {@code authcode(seKey) . $id}）；前台所有校验点
 * 只用默认槽 {@link #DEFAULT_SLOT}。中文验证码（{@code useZh}）与英文共用同一套记录与校验规则。
 *
 * <p>校验语义由 {@code member/php{80,73}-golden.json} 的 captcha_check 用例锁定：
 * <ul>
 * <li>输入为 PHP {@code empty()}（空串或 {@code "0"}）→ 不通过，会话不变；</li>
 * <li>没有记录 → 不通过；</li>
 * <li>记录已超过 {@value #EXPIRE_SECONDS} 秒（{@code 现在 − 生成时刻 > 1800}，等于 1800 仍有效）→ 删除记录，不通过；</li>
 * <li>输入按 ASCII 转大写后与记录相同（不去空白）→ <b>删除记录</b>，通过（同一验证码不能再用第二次）；</li>
 * <li>不相同 → 不通过。<b>与旧版不同</b>：旧版保留记录，30 分钟内可以对同一个 4 位数字验证码无限次猜测；新版同样删除记录，
 * 猜错一次就必须换新验证码（D-B08-4）。</li>
 * </ul>
 * 中文码按 Java {@code char} 比较：旧版生成和校验两侧都用 {@code strtoupper}，在 C 区域设置下只改 ASCII 字母，UTF-8 的多字节
 * 序列原样比较，二者等价；所以 zhSet 里的 ASCII 小写字母不分大小写，全角字母、希腊/西里尔字母区分大小写，半角/全角、简繁不互通
 * （黄金 captcha_check 的 zh-* 用例锁定）。
 */
public final class Captchas {

    /** think-captcha 默认有效期；旧 {@code captcha_check()} 不传配置，所以总是这个值。 */
    public static final long EXPIRE_SECONDS = 1800;

    /** 旧 {@code captcha_check($code)} 不传 id 时的槽。 */
    public static final String DEFAULT_SLOT = "";

    private static final String SLOT = "captcha:";

    private Captchas() {
    }

    /** 会话里的一条验证码记录：大写后的码与生成时刻（秒）。 */
    record Issued(String codeUpper, long createdEpochSeconds) {
    }

    /**
     * 生成新验证码，写入会话的 {@code id} 槽（覆盖同槽的旧记录），返回 PNG 字节。
     */
    public static byte[] issue(SessionState session, String id, CaptchaSettings settings, long nowEpochSeconds,
                               RandomGenerator random) {
        String code = code(settings, random);
        byte[] png = CaptchaImage.render(code, settings, random);
        session.put(SLOT + slot(id), new Issued(asciiUpper(code), nowEpochSeconds));
        return png;
    }

    /**
     * 随机取 {@code length} 个字符：英文按 {@code codeSet} 字节索引（与旧版相同；码表已限定为 ASCII），中文按 {@code zhSet} 的
     * 码位索引（旧版按 UTF-8 字符），码表里重复的字被抽中的概率相应更高，与旧版一致。
     */
    static String code(CaptchaSettings settings, RandomGenerator random) {
        StringBuilder sb = new StringBuilder(settings.length() * 2);
        if (settings.useZh()) {
            int[] set = settings.zhSet().codePoints().toArray();
            for (int i = 0; i < settings.length(); i++) {
                sb.appendCodePoint(set[random.nextInt(set.length)]);
            }
            return sb.toString();
        }
        String set = settings.codeSet();
        for (int i = 0; i < settings.length(); i++) {
            sb.append(set.charAt(random.nextInt(set.length())));
        }
        return sb.toString();
    }

    /** 校验并按上面的规则作废记录。{@code session} 为 null（请求没有有效会话）时不通过。 */
    public static boolean check(SessionState session, String input, String id, long nowEpochSeconds) {
        if (session == null || input == null || input.isEmpty() || input.equals("0")) {
            return false;
        }
        Issued issued = session.take(SLOT + slot(id), Issued.class).orElse(null);
        if (issued == null) {
            return false;
        }
        if (nowEpochSeconds - issued.createdEpochSeconds() > EXPIRE_SECONDS) {
            return false;
        }
        return asciiUpper(input).equals(issued.codeUpper());
    }

    private static String slot(String id) {
        return id == null ? DEFAULT_SLOT : id;
    }

    /** PHP 7.3/8.0 {@code strtoupper}（C 区域设置）只转换 ASCII 字母。 */
    static String asciiUpper(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return sb.toString();
    }
}

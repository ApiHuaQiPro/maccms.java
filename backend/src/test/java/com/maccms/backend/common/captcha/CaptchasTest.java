package com.maccms.backend.common.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.session.SessionStore;
import com.maccms.backend.member.MemberGolden;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 本地验证码（B08，17 文档第 2.4 节）对照 think-captcha 黄金样本。 */
class CaptchasTest {

    private static final long NOW = 2_000_000_000L;

    /**
     * 逐个复放黄金中的校验序列（PHP 8.0 与 7.3 结果相同）。唯一有意的差异：猜错后旧版保留验证码（{@code wrong-then-correct} 的第二次通过），
     * 新版猜错即作废（D-B08-4）。
     */
    @Test
    void checkMatchesThinkCaptchaExceptWrongGuessesBurnTheCode() {
        for (String v : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            int n = 0;
            for (JsonNode c : MemberGolden.load(v).get("captcha_check")) {
                assertThat(c.get("same_second").asBoolean()).isTrue();
                String label = c.get("label").asString();
                SessionState s = SessionStore.withDefaults(Clock.systemUTC()).create();
                JsonNode stored = c.get("stored");
                if (!stored.isNull()) {
                    s.put("captcha:" + stored.get("id").asString(), new Captchas.Issued(
                            Captchas.asciiUpper(stored.get("code").asString()), NOW - stored.get("age").asLong()));
                }
                JsonNode inputs = c.get("inputs");
                for (int i = 0; i < inputs.size(); i++) {
                    String code = new String(MemberGolden.bytes(inputs.get(i).get("code")), StandardCharsets.UTF_8);
                    boolean java = Captchas.check(s, code, inputs.get(i).get("id").asString(), NOW);
                    boolean php = c.get("checks").get(i).get("ok").asBoolean();
                    if (label.equals("wrong-then-correct") && i == 1) {
                        assertThat(php).isTrue();
                        assertThat(java).as("D-B08-4：猜错一次后验证码已作废").isFalse();
                    } else {
                        assertThat(java).as(v + " " + label + "#" + i).isEqualTo(php);
                    }
                }
                n++;
            }
            assertThat(n).isGreaterThanOrEqualTo(14);
        }
    }

    @Test
    void issuedCodeIsSingleUseAndExpiresAfter1800Seconds() {
        CaptchaSettings settings = repoSettings();
        SessionState s = SessionStore.withDefaults(Clock.systemUTC()).create();
        String code = issue(s, settings, 7, NOW);
        assertThat(code).hasSize(4).matches("[0-9]{4}");
        assertThat(Captchas.check(s, code, "", NOW + 1800)).isTrue();
        assertThat(Captchas.check(s, code, "", NOW + 1800)).as("重放").isFalse();

        issue(s, settings, 8, NOW);
        String late = issue(s, settings, 9, NOW);
        assertThat(Captchas.check(s, late, "", NOW + 1801)).as("过期").isFalse();
        assertThat(Captchas.check(null, late, "", NOW)).isFalse();

        String fresh = issue(s, settings, 10, NOW);
        assertThat(Captchas.check(s, "", "", NOW)).isFalse();
        assertThat(Captchas.check(s, "0", "", NOW)).isFalse();
        assertThat(Captchas.check(s, fresh, "", NOW)).as("空输入与 0 不消耗验证码").isTrue();
    }

    @Test
    void imageSizeFollowsTheLegacyFormula() throws Exception {
        for (JsonNode c : MemberGolden.load(MemberGolden.PHP80).get("captcha_image")) {
            Map<String, Object> legacy = new HashMap<>(Map.of("codeSet", "1234567890", "length", 4L, "fontSize", 16L,
                    "useNoise", false, "useCurve", false));
            c.get("override").properties().forEach(e -> legacy.put(e.getKey(),
                    e.getValue().isBoolean() ? e.getValue().asBoolean() : e.getValue().asLong()));
            CaptchaSettings settings = CaptchaSettings.fromLegacy(legacy);
            SessionState s = SessionStore.withDefaults(Clock.systemUTC()).create();
            byte[] png = Captchas.issue(s, "", settings, NOW, new Random(1));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(img.getWidth()).as(c.get("label").asString()).isEqualTo(c.get("width").asInt());
            assertThat(img.getHeight()).isEqualTo(c.get("height").asInt());
            assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        }
    }

    @Test
    void defaultsAndUnsupportedLegacyValues() {
        assertThat(CaptchaSettings.THINK_DEFAULTS.width()).isEqualTo(250);
        assertThat(CaptchaSettings.THINK_DEFAULTS.height()).isEqualTo(62);
        assertThat(repoSettings().width()).isEqualTo(128);
        assertThat(repoSettings().height()).isEqualTo(40);
        assertThat(CaptchaSettings.fromLegacy(Map.of("length", "6", "useCurve", "0")).length()).isEqualTo(6);
        assertThatThrownBy(() -> CaptchaSettings.fromLegacy(Map.of("useZh", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("useZh");
        assertThatThrownBy(() -> CaptchaSettings.fromLegacy(Map.of("codeSet", "一二三")))
                .hasMessageContaining("codeSet");
        assertThatThrownBy(() -> CaptchaSettings.fromLegacy(Map.of("length", 0L))).hasMessageContaining("length");
        assertThatThrownBy(() -> CaptchaSettings.fromLegacy(Map.of("fontSize", 1000L)))
                .hasMessageContaining("fontSize");
    }

    @Test
    void lettersRenderAndCompareCaseInsensitively() throws Exception {
        CaptchaSettings settings = CaptchaSettings.THINK_DEFAULTS;
        SessionState s = SessionStore.withDefaults(Clock.systemUTC()).create();
        String code = issue(s, settings, 42, NOW);
        assertThat(code).hasSize(5);
        assertThat(Captchas.check(s, code.toLowerCase(), "", NOW)).isTrue();
        // 杂点与干扰线打开时也能生成
        assertThat(ImageIO.read(new ByteArrayInputStream(Captchas.issue(s, "x", settings, NOW, new Random(3)))))
                .isNotNull();
    }

    static CaptchaSettings repoSettings() {
        return CaptchaSettings.fromLegacy(Map.of("codeSet", "1234567890", "length", 4L, "fontSize", 16L,
                "useNoise", false, "useCurve", false));
    }

    /** 用固定种子签发，并用同一种子重算出验证码（{@link Captchas#code} 先于绘图消耗随机数）。 */
    static String issue(SessionState s, CaptchaSettings settings, long seed, long now) {
        Captchas.issue(s, "", settings, now, new Random(seed));
        RandomGenerator replay = new Random(seed);
        return Captchas.code(settings, replay);
    }
}

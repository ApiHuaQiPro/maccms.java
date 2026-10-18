package com.maccms.backend.common.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.session.SessionStore;
import com.maccms.backend.member.MemberGolden;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 本地验证码（B08，17 文档第 2.4 节）对照 think-captcha 黄金样本。 */
class CaptchasTest {

    private static final long NOW = 2_000_000_000L;

    /**
     * 逐个复放黄金中的校验序列（PHP 8.0 与 7.3 结果相同），含中文验证码的 {@code zh-*} 序列。唯一有意的差异：猜错后旧版保留验证码
     * （{@code wrong-then-correct}、{@code zh-wrong-then-correct} 的第二次通过），新版猜错即作废（D-B08-4）。
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
                    if (label.endsWith("wrong-then-correct") && i == 1) {
                        assertThat(php).isTrue();
                        assertThat(java).as("D-B08-4：猜错一次后验证码已作废").isFalse();
                    } else {
                        assertThat(java).as(v + " " + label + "#" + i).isEqualTo(php);
                    }
                }
                n++;
            }
            assertThat(n).isGreaterThanOrEqualTo(22);
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
            c.get("override").properties().forEach(e -> legacy.put(e.getKey(), e.getValue().isBoolean()
                    ? e.getValue().asBoolean() : e.getValue().isString() ? e.getValue().asString() : e.getValue().asLong()));
            CaptchaSettings settings = CaptchaSettings.fromLegacy(legacy);
            if (c.has("error")) {
                // zh-backup-vendor：备份的 vendor 没有 assets/zhttfs/，旧版 entry() 出错；新版用内置点阵正常生成
                assertThat(c.get("label").asString()).isEqualTo("zh-backup-vendor");
                assertThat(settings.useZh()).isTrue();
                assertThat(ImageIO.read(new ByteArrayInputStream(Captchas.issue(SessionStore.withDefaults(
                        Clock.systemUTC()).create(), "", settings, NOW, new Random(1)))).getWidth()).isEqualTo(128);
                continue;
            }
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
        assertThat(CaptchaSettings.fromLegacy(Map.of("useZh", true)).useZh()).isTrue();
        assertThat(CaptchaSettings.fromLegacy(Map.of("useZh", "1", "codeSet", "一二三")).zhSet())
                .as("中文模式不用 codeSet").isEqualTo(CaptchaSettings.THINK_DEFAULTS.zhSet());
        assertThatThrownBy(() -> CaptchaSettings.fromLegacy(Map.of("codeSet", "一二三")))
                .hasMessageContaining("codeSet");
        assertThat(CaptchaSettings.fromLegacy(Map.of("useZh", true, "zhSet", "中文abc")).zhSet())
                .as("中文码表可以含 ASCII（旧 1.ttf 能画）").isEqualTo("中文abc");
        assertThatThrownBy(() -> CaptchaSettings.fromLegacy(Map.of("useZh", true, "zhSet", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("zhSet");
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

    @Test
    void thinkZhSetIsCopiedVerbatimAndFullyCovered() {
        String zh = CaptchaSettings.THINK_DEFAULTS.zhSet();
        // think-captcha Captcha.php 的 $config['zhSet']：1500 个字（UTF-8 4500 字节），md5 与旧文件中的值相同
        assertThat(zh.codePointCount(0, zh.length())).isEqualTo(1500);
        assertThat(zh.getBytes(StandardCharsets.UTF_8)).hasSize(4500);
        assertThat(md5(zh)).isEqualTo("7cc4f3b48ea97abe6ade16eb30f365cb");
        assertThat(zh.codePoints().distinct().count()).isEqualTo(1487);
        assertThat(zh.codePoints().allMatch(ZhGlyphs::has)).isTrue();
        assertThat(ZhGlyphs.rows('一')).as("Unifont U+4E00").containsExactly(0, 0, 0, 0, 0, 0, 0, 0xFFFE, 0, 0, 0, 0,
                0, 0, 0, 0);
        assertThat(ZhGlyphs.glyph('a').width()).as("ASCII 是 8×16 半宽字").isEqualTo(8);
        assertThat(ZhGlyphs.rows('a')).as("Unifont U+0061，半宽字只用左 8 位").containsExactly(0, 0, 0, 0, 0, 0,
                0x3C00, 0x4200, 0x0200, 0x3E00, 0x4200, 0x4200, 0x4600, 0x3A00, 0, 0);
        assertThat(ZhGlyphs.glyph('，').width()).isEqualTo(16);
    }

    /**
     * 内置字形的范围与旧 think-captcha v1.0.7 {@code zhttfs/1.ttf} 能画的字相同（不含私用区）：GB2312 全部汉字与符号、ASCII
     * 0x20–0x7E、全角 U+FF01–U+FF5E，以及 1.ttf 按 GBK 映射的小写罗马数字与竖排标点。1.ttf 没有的繁体、扩展区汉字、私用区，
     * 以及 GB2312 A1A4、A1AA 的另一种映射 U+30FB、U+2015（1.ttf 映射为 U+00B7、U+2014）没有内置字形。
     */
    @Test
    void builtInGlyphsCoverWhatTheLegacyFontCanDraw() {
        Charset gb2312 = Charset.forName("GB2312");
        int gb = 0;
        for (int hi = 0xA1; hi <= 0xF7; hi++) {
            for (int lo = 0xA1; lo <= 0xFE; lo++) {
                CharsetDecoder d = gb2312.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                int cp;
                try {
                    cp = d.decode(ByteBuffer.wrap(new byte[] {(byte) hi, (byte) lo})).toString().codePointAt(0);
                } catch (CharacterCodingException e) {
                    continue;
                }
                gb++;
                if (cp != 0x30FB && cp != 0x2015) {
                    assertThat(ZhGlyphs.has(cp)).as("GB2312 %02X%02X U+%04X", hi, lo, cp).isTrue();
                }
            }
        }
        assertThat(gb).isEqualTo(6763 + 682);
        for (int cp = 0x20; cp < 0x7F; cp++) {
            assertThat(ZhGlyphs.has(cp)).as("ASCII %02X", cp).isTrue();
        }
        for (int cp = 0xFF01; cp <= 0xFF5E; cp++) {
            assertThat(ZhGlyphs.has(cp)).as("全角 U+%04X", cp).isTrue();
        }
        assertThat(ZhGlyphs.missing("ⅰⅹ︱﹄·—")).isEmpty();
        assertThat(ZhGlyphs.missing("們國龍㐀𠀀―・們")).as("1.ttf 也没有，去重")
                .containsExactly((int) '們', (int) '國', (int) '龍', 0x3400, 0x20000, 0xE000, 0x2015, 0x30FB);
    }

    /**
     * zhSet 混有 ASCII、全角标点与字母、繁体与扩展区汉字：配置照常加载（不启动失败），码表原样保留，码字照常从中抽取；有字形的字
     * 照画，没有字形的字按单字画缺字框；校验语义不变（旧版两侧都用 strtoupper：ASCII 字母不分大小写，全角字母区分）。
     */
    @Test
    void mixedZhSetLoadsDrawsMissingGlyphBoxesAndChecksLikeTheLegacy() throws Exception {
        String mixed = "我A，ａ們㐀𠀀";
        CaptchaSettings settings = zh(mixed);
        assertThat(settings.zhSet()).isEqualTo(mixed);
        assertThat(settings.missingZhGlyphs()).containsExactly((int) '們', 0x3400, 0x20000);
        assertThat(repoSettings().missingZhGlyphs()).isEmpty();
        assertThat(CaptchaSettings.THINK_DEFAULTS.missingZhGlyphs()).isEmpty();

        SessionState s = SessionStore.withDefaults(Clock.systemUTC()).create();
        Set<Integer> seen = new HashSet<>();
        for (int seed = 0; seed < 60; seed++) {
            String code = issue(s, settings, seed, NOW);
            assertThat(code.codePointCount(0, code.length())).isEqualTo(4);
            code.codePoints().forEach(seen::add);
            assertThat(Captchas.check(s, code, "", NOW)).as(code).isTrue();
        }
        assertThat(seen).as("每个字（含缺字形的字）都会被抽到")
                .containsExactlyInAnyOrderElementsOf(mixed.codePoints().boxed().toList());

        // 每种字单独成码：图上都有墨迹，校验按旧规则（第 0 项通过，第 1 项不通过且作废）
        Map<String, List<String>> cases = new LinkedHashMap<>();
        cases.put("A", List.of("a", "Ａ"));
        cases.put("a", List.of("A", "ａ"));
        cases.put("，", List.of("，", ","));
        cases.put("ａ", List.of("ａ", "Ａ"));
        cases.put("們", List.of("們", "们"));
        cases.put("㐀", List.of("㐀", "?"));
        cases.put("𠀀", List.of("𠀀", "\uD840"));
        for (Map.Entry<String, List<String>> e : cases.entrySet()) {
            CaptchaSettings one = zh(e.getKey());
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(Captchas.issue(s, "", one, NOW, new Random(9))));
            assertThat(img.getWidth()).isEqualTo(128);
            assertThat(ink(img)).as(e.getKey() + " 画出来了").isGreaterThan(4 * 8);
            assertThat(Captchas.check(s, e.getValue().get(0).repeat(4), "", NOW)).as(e.getKey()).isTrue();
            Captchas.issue(s, "", one, NOW, new Random(9));
            assertThat(Captchas.check(s, e.getValue().get(1).repeat(4), "", NOW)).as(e.getKey() + " 错").isFalse();
            assertThat(Captchas.check(s, e.getValue().get(0).repeat(4), "", NOW)).as("猜错即作废").isFalse();
        }

        // 所有缺字形的字画同一个缺字框，与空白、与有字形的字都不同
        assertThat(render("們")).isEqualTo(render("㐀")).isNotEqualTo(render("我")).isNotEqualTo(render(" "));
    }

    private static CaptchaSettings zh(String zhSet) {
        return CaptchaSettings.fromLegacy(Map.of("useZh", true, "zhSet", zhSet, "length", 4L, "fontSize", 16L,
                "useNoise", false, "useCurve", false));
    }

    private static int ink(BufferedImage img) {
        int bg = img.getRGB(0, 0);
        int ink = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                ink += img.getRGB(x, y) != bg ? 1 : 0;
            }
        }
        return ink;
    }

    private static List<Integer> render(String zhSet) throws Exception {
        SessionState s = SessionStore.withDefaults(Clock.systemUTC()).create();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Captchas.issue(s, "", zh(zhSet), NOW,
                new Random(11))));
        return Arrays.stream(img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth())).boxed()
                .toList();
    }

    @Test
    void zhCodeIsDrawnPerCharacterFromZhSetAndChecksLikeTheLegacy() throws Exception {
        CaptchaSettings settings = CaptchaSettings.fromLegacy(Map.of("codeSet", "1234567890", "length", 4L,
                "fontSize", 16L, "useNoise", false, "useCurve", false, "useZh", true));
        SessionState s = SessionStore.withDefaults(Clock.systemUTC()).create();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int seed = 0; seed < 50; seed++) {
            String code = issue(s, settings, seed, NOW);
            assertThat(code.codePointCount(0, code.length())).isEqualTo(4);
            assertThat(code.codePoints()).allMatch(cp -> settings.zhSet().indexOf(cp) >= 0);
            code.codePoints().forEach(seen::add);
            assertThat(Captchas.check(s, code, "", NOW + 1800)).isTrue();
            assertThat(Captchas.check(s, code, "", NOW)).as("重放").isFalse();
        }
        assertThat(seen.size()).as("不是固定的几个字").isGreaterThan(100);

        String code = issue(s, settings, 7, NOW);
        assertThat(Captchas.check(s, code, "", NOW + 1801)).as("过期").isFalse();
        code = issue(s, settings, 8, NOW);
        assertThat(Captchas.check(s, new StringBuilder(code).reverse() + "x", "", NOW)).isFalse();
        assertThat(Captchas.check(s, code, "", NOW)).as("猜错即作废").isFalse();

        CaptchaSettings one = CaptchaSettings.fromLegacy(Map.of("useZh", true, "zhSet", "我", "length", 3L));
        assertThat(Captchas.code(one, new Random(1))).isEqualTo("我我我");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Captchas.issue(s, "", settings, NOW,
                new Random(5))));
        assertThat(img.getWidth()).isEqualTo(128);
        assertThat(img.getHeight()).isEqualTo(40);
        // 默认版式（杂点、干扰线、字号 25）也能生成
        CaptchaSettings full = CaptchaSettings.fromLegacy(Map.of("useZh", true));
        assertThat(ImageIO.read(new ByteArrayInputStream(Captchas.issue(s, "", full, NOW, new Random(6))))
                .getWidth()).isEqualTo(250);
    }

    private static String md5(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

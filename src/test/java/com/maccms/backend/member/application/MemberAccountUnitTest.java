package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.member.application.view.MemberGroup;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** B09 会员账号的纯计算部分：旧配置读取、IP、文本还原、头像检查与转换、可空会员组的 cookie。 */
class MemberAccountUnitTest {

    private static MemberSettings settings(Map<String, PhpValue> user) {
        Map<Object, PhpValue> u = new LinkedHashMap<>();
        user.forEach((k, v) -> u.put(PhpArray.normalizeKey(k), v));
        return MemberSettings.from(new PhpArray(Map.of("user", new PhpArray(u))));
    }

    private static PhpValue s(String v) {
        return new PhpValue.PhpString(v);
    }

    @Test
    void settingsUseLegacyLooseComparisons() {
        assertThat(MemberSettings.from(null)).isEqualTo(MemberSettings.REPOSITORY);
        MemberSettings m = settings(Map.of("status", s("1"), "reg_open", s(" 0"), "reg_verify", s("1.0"),
                "login_verify", s("on"), "reg_points", s("12abc"), "reg_num", s("3"), "portrait_status", s("0"),
                "portrait_size", s("120X80"), "reg_status", s("")));
        assertThat(m.registrationClosed()).as("' 0' == 0").isTrue();
        assertThat(m.regVerify()).as("'1.0' == 1").isTrue();
        assertThat(m.loginVerify()).as("非数值串不等于 1（PHP 8）").isFalse();
        assertThat(m.regPoints()).isEqualTo(12);
        assertThat(m.regNum()).isEqualTo(3);
        assertThat(m.regNumText()).isEqualTo("3");
        assertThat(m.portraitZero()).isTrue();
        assertThat(m.portraitWidth()).isEqualTo(120);
        assertThat(m.portraitHeight()).isEqualTo(80);
        assertThat(m.regStatus()).isZero();
        assertThat(MemberSettings.portraitSize("64")).containsExactly(64, 64);
        assertThat(MemberSettings.portraitSize("0x10")).containsExactly(0, 0);
        assertThat(MemberSettings.portraitSize("5000x10")).as("上限 1000").containsExactly(0, 0);
        assertThat(MemberSettings.portraitSize("1x2x3")).containsExactly(0, 0);
        assertThat(MemberSettings.portraitSize("a")).containsExactly(0, 0);
    }

    @Test
    void legacyIpLongIsIpv4OnlyLikeIp2long() {
        assertThat(MemberAccountService.legacyIp("127.0.0.1")).isEqualTo(2130706433L);
        assertThat(MemberAccountService.legacyIp("255.255.255.254")).isEqualTo(4294967294L);
        assertThat(MemberAccountService.legacyIp("255.255.255.255")).as("旧版 >= 0xFFFFFFFF 记 0").isZero();
        assertThat(MemberAccountService.legacyIp("::1")).isZero();
        assertThat(MemberAccountService.legacyIp("1.2.3")).isZero();
        assertThat(MemberAccountService.legacyIp("01.2.3.4")).isZero();
        assertThat(MemberAccountService.legacyIp(null)).isZero();
    }

    @Test
    void cleanAndUncleanRoundTrip() {
        String cleaned = LegacyText.clean(" a%26b<\"c\">'+ ".getBytes(StandardCharsets.UTF_8));
        assertThat(cleaned).isEqualTo("a&amp;b&lt;&quot;c&quot;&gt;' ");
        assertThat(LegacyText.unclean(cleaned)).isEqualTo("a&b<\"c\">' ");
        assertThat(LegacyText.clean(LegacyText.unclean(cleaned).getBytes(StandardCharsets.UTF_8))).isEqualTo(cleaned
                .strip());
        assertThat(LegacyText.unclean("&amp;amp;&#39;")).isEqualTo("&amp;&#39;");
        assertThat(LegacyText.phpTrim("\t\u0000 x \u000B\n")).isEqualTo("x");
        assertThat(LegacyText.md5("")).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    @Test
    void portraitChecksFollowTheLegacyOrderAndReencode() throws Exception {
        PortraitImages images = new PortraitImages(50, 40);
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        PortraitImages.Outcome ok = images.convert(png.toByteArray(), "a.PNG", null);
        assertThat(ok.error()).isNull();
        BufferedImage jpeg = ImageIO.read(new ByteArrayInputStream(ok.jpeg()));
        assertThat(jpeg.getWidth()).isEqualTo(50);
        assertThat(jpeg.getHeight()).isEqualTo(40);
        assertThat(ok.jpeg()[0] & 0xFF).as("JPEG").isEqualTo(0xFF);
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        assertThat(images.convert(null, null, dataUrl).error()).as("imgdata 优先").isNull();
        assertThat(images.convert(null, null, "data:image/svg;base64,AAAA").error())
                .isEqualTo(PortraitImages.FORBIDDEN_EXT);
        assertThat(images.convert(null, null, "nope").error()).isEqualTo(PortraitImages.NO_INPUT);
        assertThat(images.convert(null, null, null).error()).isEqualTo(PortraitImages.NO_INPUT);
        assertThat(images.convert(new byte[] {1}, "a.exe", null).error()).isEqualTo(PortraitImages.FORBIDDEN_EXT);
        assertThat(images.convert("hello".getBytes(StandardCharsets.UTF_8), "a.txt", null).error())
                .as("允许的扩展名但不是图片").isEqualTo(PortraitImages.FAILED);
        assertThat(images.convert("<?PHP x".getBytes(StandardCharsets.UTF_8), "a.jpg", null).error())
                .isEqualTo(PortraitImages.FORBIDDEN_EXT);
        byte[] script = ("GIF89a" + "<script>").getBytes(StandardCharsets.US_ASCII);
        assertThat(images.convert(script, "a.gif", null).error()).isEqualTo(PortraitImages.UNSAFE);
        assertThat(PortraitImages.unsafe("<?php echo(1); ?>".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(PortraitImages.unsafe("<Script>".getBytes(StandardCharsets.US_ASCII)))
                .as("旧正则只认全大写或全小写").isFalse();
        assertThat(PortraitImages.extension("dir.x/a.Jpeg")).isEqualTo("jpeg");
        assertThat(PortraitImages.extension("noext")).isEmpty();
    }

    @Test
    void portraitAnnexTypeFollowsTheLegacyExtensionClass() {
        // 旧 Upload.php:144-152：按扩展名分 image / file / media，头像登记的 annex_type 取这个分类（PHP 实跑黄金：n.txt 里是图片时登记 file）
        assertThat(PortraitImages.annexType("a.JPG", null)).isEqualTo("image");
        assertThat(PortraitImages.annexType("w.webp", null)).isEqualTo("image");
        assertThat(PortraitImages.annexType("n.txt", null)).isEqualTo("file");
        assertThat(PortraitImages.annexType("d.DOCX", null)).isEqualTo("file");
        assertThat(PortraitImages.annexType("v.Mp4", null)).isEqualTo("media");
        assertThat(PortraitImages.annexType("s.rmvb", null)).isEqualTo("media");
        // imgdata：data URL 里的类型（Upload.php:118-119 的 $type = $result[2]）
        assertThat(PortraitImages.annexType(null, "data:image/png;base64,AAAA")).isEqualTo("png");
        assertThat(PortraitImages.annexType("a.txt", "data: image/jpeg;base64,AAAA")).as("imgdata 优先").isEqualTo("jpeg");
    }

    @Test
    void cookiesForAMissingOrNamelessGroupFollowThinkPhpCookieHelper() {
        List<String> missing = MemberCookies.login(5, "u", (MemberGroup) null, "r", "/p", 2_000_000_000L);
        assertThat(missing.get(2)).startsWith("group_id=deleted;");
        assertThat(missing.get(3)).startsWith("group_name=deleted;");
        assertThat(missing).hasSize(6);
        List<String> nameless = MemberCookies.login(5, "u", new MemberGroup(4, "", 1, "", "{}"), "r", "/p",
                2_000_000_000L);
        assertThat(nameless).hasSize(5);
        assertThat(nameless.get(2)).startsWith("group_id=4;");
        assertThat(nameless.get(3)).startsWith("user_check=");
        assertThat(MemberCookies.groupRefresh(null, 2_000_000_000L)).hasSize(2).allMatch(h -> h.contains("=deleted;"));
        assertThat(MemberCookies.login(5, "u", new MemberGroup(2, "默认会员", 1, "", "{}"), "r", "/p", 2_000_000_000L))
                .isEqualTo(MemberCookies.login(5, "u", 2, "默认会员", "r", "/p", 2_000_000_000L));
    }

    @Test
    void sitePathMustBeAbsoluteDirectory() {
        assertThat(MemberAccountConfiguration.sitePath("https://a.test/sub")).isEqualTo("/sub/");
        assertThat(MemberAccountConfiguration.sitePath("https://a.test")).isEqualTo("/");
        assertThatThrownBy(() -> new MemberAccountService(null, null, null, null, MemberSettings.REPOSITORY, null, null,
                "sub/")).isInstanceOf(IllegalArgumentException.class);
    }
}

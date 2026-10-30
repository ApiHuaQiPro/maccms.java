package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.site.application.ImageUrlNormalizer.Outcome;
import java.util.Optional;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 黄金向量之外的规则：配置校验、长度与编码边界、字符引用解码器。PHP 对拍见 {@link ImageUrlGoldenVectorsTest}。 */
class ImageUrlNormalizerTest {

    private static final SiteSettings SITE = ImageUrlGoldenVectorsTest.site("https://www.maccms.test/");
    private static final ImageUrlNormalizer LOCAL = ImageUrlNormalizer.create(
            new ImageUrlConfig("", "local", "", "", "/img.php?url="), SITE);

    @Test
    void nullConfigValuesMeanUnset() {
        ImageUrlNormalizer n = ImageUrlNormalizer.create(new ImageUrlConfig(null, null, null, null, null), SITE);
        assertThat(n.normalize("mac://img.example.test/a.jpg")).map(SafeUrl::href)
                .hasValue("http://img.example.test/a.jpg");
        assertThat(n.normalize("upload/a.jpg")).map(SafeUrl::href).hasValue("/upload/a.jpg");
    }

    @Test
    void configErrorsListEveryKeyWithoutEchoingValues() {
        ImageUrlConfig bad = new ImageUrlConfig("SYNTHETIC-SECRET-1", "remote", "https://SYNTHETIC-SECRET-2.test",
                "SYNTHETIC-SECRET-3/x", "javascript:SYNTHETIC-SECRET-4");
        assertThatThrownBy(() -> ImageUrlNormalizer.create(bad, SITE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upload.protocol")
                .hasMessageContaining("upload.remoteurl")
                .hasMessageContaining("upload.img_key")
                .hasMessageContaining("upload.img_api")
                .hasMessageNotContaining("SYNTHETIC-SECRET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://img.test/a/?x=", "https://img.test/a#b/", "https://img.test/a&b/",
            "https://u@img.test/", "ftp://img.test/", "/upload/", "///img.test/"})
    void remoteUrlMustBeAnAbsoluteDirectory(String remoteUrl) {
        assertThatThrownBy(() -> ImageUrlNormalizer.create(new ImageUrlConfig("", "remote", remoteUrl, "", ""), SITE))
                .hasMessageContaining("upload.remoteurl");
    }

    @Test
    void remoteUrlIsIgnoredOutsideRemoteMode() {
        ImageUrlNormalizer n = ImageUrlNormalizer.create(new ImageUrlConfig("", "local", "not a url", "", ""), SITE);
        assertThat(n.normalize("upload/a.jpg")).map(SafeUrl::href).hasValue("/upload/a.jpg");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/b", "a\\", "a\\\\\\", "a(", "[", "a\nb"})
    void imgKeyMustCompileLikePhp(String imgKey) {
        assertThatThrownBy(() -> ImageUrlNormalizer.create(new ImageUrlConfig("", "", "", imgKey, ""), SITE))
                .hasMessageContaining("upload.img_key");
    }

    @Test
    void imgKeyAcceptsEscapedSlashAndEvenBackslashes() {
        ImageUrlNormalizer n = ImageUrlNormalizer.create(
                new ImageUrlConfig("", "", "", "cdn\\.example\\.test\\/p\\\\", "/img.php?url="), SITE);
        assertThat(n.resolve("https://cdn.example.test/p\\x").branch()).isEqualTo(ImageUrlNormalizer.Branch.IMG_API);
        // 命中后结果含反斜线，SafeUrl 拒绝
        assertThat(n.resolve("https://cdn.example.test/p\\x").outcome()).isEqualTo(Outcome.NOT_ALLOWED);
    }

    @Test
    void imgApiIsOnlyValidatedWhenImgKeyIsSet() {
        ImageUrlNormalizer.create(new ImageUrlConfig("", "", "", "", "javascript:x"), SITE);
        assertThatThrownBy(() -> ImageUrlNormalizer.create(new ImageUrlConfig("", "", "", "k", "javascript:x"), SITE))
                .hasMessageContaining("upload.img_api");
    }

    @Test
    void protocolMustBeLowercaseHttpOrHttps() {
        assertThatThrownBy(() -> ImageUrlNormalizer.create(new ImageUrlConfig("HTTPS", "", "", "", ""), SITE))
                .hasMessageContaining("upload.protocol");
        assertThat(ImageUrlNormalizer.create(new ImageUrlConfig("0", "", "", "", ""), SITE)
                .normalize("mac://img.example.test/a.jpg")).map(SafeUrl::href).hasValue("http://img.example.test/a.jpg");
    }

    @Test
    void subdirectorySiteUsesItsPathAsLocalPrefix() {
        ImageUrlNormalizer n = ImageUrlNormalizer.create(new ImageUrlConfig("", "", "", "", ""),
                ImageUrlGoldenVectorsTest.site("http://maccms.local.test/sub/"));
        assertThat(n.normalize("upload/a.jpg")).map(SafeUrl::href).hasValue("/sub/upload/a.jpg");
        assertThat(n.normalize("//img.example.test/a.jpg")).map(SafeUrl::href).hasValue("http://img.example.test/a.jpg");
    }

    @Test
    void overlongResultIsRejected() {
        assertThat(LOCAL.resolve("https://img.example.test/" + "a".repeat(SafeUrl.MAX_LENGTH)).outcome())
                .isEqualTo(Outcome.NOT_ALLOWED);
    }

    @Test
    void unpairedSurrogateIsRejected() {
        assertThat(LOCAL.resolve("upload/a\uD800.jpg").outcome()).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(LOCAL.resolve("upload/a&#xD800;.jpg").outcome()).isEqualTo(Outcome.UNSUPPORTED_CHAR_REF);
        assertThat(LOCAL.normalize("upload/😀.jpg")).map(SafeUrl::href).hasValue("/upload/😀.jpg");
    }

    @Test
    void edgeWhitespaceProducedByCharRefsIsStrippedLikeBrowsers() {
        assertThat(LOCAL.normalize("https://img.example.test/a.jpg&#32;")).map(SafeUrl::href)
                .hasValue("https://img.example.test/a.jpg");
        assertThat(LOCAL.resolve("https://img.example.test/a&#9;b.jpg").outcome()).isEqualTo(Outcome.NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"&#0;", "&#x110000;", "&#99999999999999999999;", "&#128;", "&#x9F;", "&#xDFFF;",
            "&hellip;", "&nbsp;", "&copy", "&para.", "&ampfoo;", "&b;", "&123;"})
    void unsupportedCharRefs(String ref) {
        assertThat(HtmlAttributeCharRefs.decode("/a" + ref)).isEmpty();
    }

    /** 解码器与 jsoup（属性模式）对同一输入的结果一致；样本覆盖数字/命名、有无分号、后接 = 或字母数字等情形。 */
    @ParameterizedTest
    @ValueSource(strings = {"/a?x=1&b=2", "/a?x=1&amp;b=2", "/a?x=1&AMP;b", "/a&amp", "/a&amp.b", "/a?c&copy=1",
            "/a?n&notify=1", "/a?n&not1", "/a&#38;b", "/a&#38b", "/a&#x26;b", "/a&#X26b", "/a&#;b", "/a&#x;b", "/a&#xg",
            "/a&;", "/a&", "/a&&", "/a&quot;b", "/a&quot", "/a&lt;b", "/a&GT;", "/a&apos;", "/a&#20013;&#x6587;"})
    void decoderAgreesWithJsoupForSupportedInputs(String s) {
        Optional<String> ours = HtmlAttributeCharRefs.decode(s);
        assertThat(ours).as(s).isPresent();
        assertThat(ours.get()).as(s).isEqualTo(Parser.unescapeEntities(s, true));
    }

    @Test
    void legacyNamesMatchJsoupBaseEntities() {
        assertThat(HtmlAttributeCharRefs.LEGACY_NAMES).hasSize(106)
                .allSatisfy(name -> assertThat(Entities.isBaseNamedEntity(name)).as(name).isTrue());
    }
}

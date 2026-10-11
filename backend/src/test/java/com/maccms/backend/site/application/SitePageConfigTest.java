package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** maccms.site.url.* 与 maccms.site.image.* 的绑定校验（MACC-14）：错误只报配置项名称，不回显取值。 */
class SitePageConfigTest {

    static final String SECRET = "SYNTHETIC-SECRET-51c0";

    private static SiteSettings site(String baseUrl) {
        return SiteSettings.from(new SiteProperties("true", "合成站", baseUrl, null, null, null, null, null, null, null,
                null));
    }

    @Test
    void urlStyleDefaultsToRepositoryDefault() {
        assertThat(new SiteUrlProperties(null, null).toStyle()).isEqualTo(LegacyUrlStyle.REPO_DEFAULT);
        assertThat(new SiteUrlProperties("", " ").toStyle()).isEqualTo(LegacyUrlStyle.REPO_DEFAULT);
        assertThat(new SiteUrlProperties(" TRUE ", "false").toStyle())
                .isEqualTo(new LegacyUrlStyle(true, false, false, '/'));
        assertThat(new SiteUrlProperties("false", "True").toStyle())
                .isEqualTo(new LegacyUrlStyle(false, true, false, '/'));
    }

    @Test
    void invalidUrlFlagsListOnlyNames() {
        assertThatThrownBy(() -> new SiteUrlProperties("1", SECRET).toStyle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.url.rewrite")
                .hasMessageContaining("maccms.site.url.suffix-hide")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET));
    }

    @Test
    void imageDefaultsNormalizeRelativePathsUnderSitePath() {
        ImageUrlNormalizer n = new SiteImageProperties(null, null, null, null, null)
                .toNormalizer(site("https://www.maccms.test/sub/"));
        assertThat(n.normalize("upload/a.jpg")).contains(new SafeUrl("/sub/upload/a.jpg"));
        assertThat(n.normalize("mac://img.maccms.test/a.jpg")).contains(new SafeUrl("http://img.maccms.test/a.jpg"));
        assertThat(n.normalize("javascript:alert(1)\"")).isEmpty();
    }

    @Test
    void imageRemoteModeAndLiteralImgKey() {
        ImageUrlNormalizer n = new SiteImageProperties("https", "remote", "https://cdn.maccms.test/", "img\\.a\\.test|b\\.test",
                "/img.php?url=").toNormalizer(site("https://www.maccms.test/"));
        assertThat(n.normalize("upload/a.jpg")).contains(new SafeUrl("https://cdn.maccms.test/upload/a.jpg"));
        assertThat(n.normalize("https://img.a.test/x.jpg")).contains(new SafeUrl("/img.php?url=https://img.a.test/x.jpg"));
        assertThat(n.normalize("https://c.test/x.jpg")).contains(new SafeUrl("https://c.test/x.jpg"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"(a+)+$", "a*", "img.[a-z]+.test", "(?:x)", "a{1,9}", "a?b", "^x", "a b"})
    void imgKeyWithQuantifiersGroupsOrClassesIsRejected(String key) {
        assertThatThrownBy(() -> new SiteImageProperties(null, null, null, key + SECRET, null)
                .toNormalizer(site("https://www.maccms.test/")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.image.img-key")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain(key));
    }

    @Test
    void normalizerConfigErrorsUseSitePropertyNames() {
        assertThatThrownBy(() -> new SiteImageProperties("ftp" + SECRET, "remote", "javascript:" + SECRET,
                "img\\.a\\.test", "javascript:" + SECRET).toNormalizer(site("https://www.maccms.test/")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.image.protocol")
                .hasMessageContaining("maccms.site.image.remote-url")
                .hasMessageContaining("maccms.site.image.img-api")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain("upload."));
    }
}

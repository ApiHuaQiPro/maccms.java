package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 站点配置的默认值、必填项与非法值（04 报告第 6.2 节、第 9 节）。错误只报配置项名称，不回显取值。 */
class SiteSettingsTest {

    static final String SECRET = "SYNTHETIC-SECRET-51e0";

    private static SiteProperties props(String name, String baseUrl) {
        return new SiteProperties("true", name, baseUrl, null, null, null, null, null, null, null, null);
    }

    private static SiteProperties with(String field, String value) {
        String[] v = {"true", "合成站", "http://maccms.local.test/", "", "", "", "", "", "", "", ""};
        String[] names = {"enabled", "name", "baseUrl", "keywords", "description", "icp", "logoUrl", "status",
                "closedMessage", "canonicalRedirect", "hitCounterEnabled"};
        v[java.util.Arrays.asList(names).indexOf(field)] = value;
        return new SiteProperties(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10]);
    }

    @Test
    void minimalConfigurationUsesDefaults() {
        SiteSettings s = SiteSettings.from(props("  合成站  ", "http://maccms.local.test/"));
        assertThat(s.name()).isEqualTo("合成站");
        assertThat(s.baseUrl().href()).isEqualTo("http://maccms.local.test/");
        assertThat(s.homePath().href()).isEqualTo("/");
        assertThat(s.keywords()).isEmpty();
        assertThat(s.description()).isEmpty();
        assertThat(s.icp()).isEmpty();
        assertThat(s.logo()).isEmpty();
        assertThat(s.closed()).isFalse();
        assertThat(s.closedMessage()).isEqualTo(SiteSettings.DEFAULT_CLOSED_MESSAGE);
    }

    @Test
    void subdirectoryBaseUrlKeepsPathForHomeAndCanonical() {
        SiteSettings s = SiteSettings.from(props("合成站", "https://maccms.local.test:8443/sub/"));
        assertThat(s.homePath().href()).isEqualTo("/sub/");
        assertThat(s.canonical("_site/placeholder").href()).isEqualTo("https://maccms.local.test:8443/sub/_site/placeholder");
        assertThat(s.canonical("").href()).isEqualTo("https://maccms.local.test:8443/sub/");
        // 页面内链接与 canonical 使用同一对外地址空间
        assertThat(s.publicPath("site/site.css").href()).isEqualTo("/sub/site/site.css");
        assertThat(SiteSettings.from(props("合成站", "http://maccms.local.test/")).publicPath("site/site.css").href())
                .isEqualTo("/site/site.css");
    }

    @Test
    void missingRequiredValuesFailWithKeyNames() {
        assertThatThrownBy(() -> SiteSettings.from(props(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.name")
                .hasMessageContaining("maccms.site.base-url");
        assertThatThrownBy(() -> SiteSettings.from(props("   ", "  ")))
                .hasMessageContaining("maccms.site.name")
                .hasMessageContaining("maccms.site.base-url");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "maccms.local.test/", "/relative/", "ftp://maccms.local.test/", "javascript:alert(1)//",
            "http://maccms.local.test", "http://maccms.local.test/sub", "http://u:" + SECRET + "@maccms.local.test/",
            "http://maccms.local.test/?q=" + SECRET, "http://maccms.local.test/#" + SECRET,
            "http://maccms.local.test/?", "http://maccms.local.test/#", "//maccms.local.test/",
            "http://maccms.local.test/a//", "http://maccms.local.test/../", "http://maccms.local.test/%2e%2e/",
            "HTTP://maccms.local.test/", "http://maccms.local.test/a b/", "http:///" + SECRET + "/"})
    void invalidBaseUrlFailsWithoutEchoingValue(String baseUrl) {
        assertThatThrownBy(() -> SiteSettings.from(props("合成站", baseUrl)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.base-url")
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining(baseUrl);
    }

    @Test
    void invalidNameFails() {
        assertThatThrownBy(() -> SiteSettings.from(props("名".repeat(65), "http://maccms.local.test/")))
                .hasMessageContaining("maccms.site.name");
        assertThatThrownBy(() -> SiteSettings.from(props("合成\u0000" + SECRET, "http://maccms.local.test/")))
                .hasMessageContaining("maccms.site.name").hasMessageNotContaining(SECRET);
        assertThat(SiteSettings.from(props("名".repeat(64), "http://maccms.local.test/")).name()).hasSize(64);
    }

    @Test
    void optionalTextLimits() {
        assertThatThrownBy(() -> SiteSettings.from(with("keywords", "k".repeat(201))))
                .hasMessageContaining("maccms.site.keywords");
        assertThatThrownBy(() -> SiteSettings.from(with("description", "d".repeat(501))))
                .hasMessageContaining("maccms.site.description");
        assertThatThrownBy(() -> SiteSettings.from(with("icp", "i".repeat(65))))
                .hasMessageContaining("maccms.site.icp");
        assertThatThrownBy(() -> SiteSettings.from(with("description", "a\u001b[31m" + SECRET)))
                .hasMessageContaining("maccms.site.description").hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> SiteSettings.from(with("closedMessage", "m".repeat(201))))
                .hasMessageContaining("maccms.site.closed-message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "//evil.example/logo.png", "/\\evil.example/logo.png",
            "data:image/png;base64,AAAA", "logo.png", "http://u:p@img.example.test/logo.png"})
    void invalidLogoUrlFails(String logo) {
        assertThatThrownBy(() -> SiteSettings.from(with("logoUrl", logo)))
                .hasMessageContaining("maccms.site.logo-url")
                .hasMessageNotContaining(logo);
    }

    @Test
    void validLogoUrls() {
        assertThat(SiteSettings.from(with("logoUrl", "/static/logo.png")).logo()).hasValue(new SafeUrl("/static/logo.png"));
        assertThat(SiteSettings.from(with("logoUrl", "https://img.example.test/logo.png")).logo()).isPresent();
    }

    @Test
    void statusValues() {
        assertThat(SiteSettings.from(with("status", "open")).closed()).isFalse();
        assertThat(SiteSettings.from(with("status", "closed")).closed()).isTrue();
        assertThat(SiteSettings.from(with("status", "")).closed()).isFalse();
        assertThatThrownBy(() -> SiteSettings.from(with("status", "0" + SECRET)))
                .hasMessageContaining("maccms.site.status").hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> SiteSettings.from(with("status", "CLOSED"))).hasMessageContaining("maccms.site.status");
    }

    @Test
    void closedMessageOverridesDefault() {
        assertThat(SiteSettings.from(with("closedMessage", " 升级中 ")).closedMessage()).isEqualTo("升级中");
    }

    @Test
    void unapprovedFeaturesMustStayOff() {
        assertThat(SiteSettings.from(with("canonicalRedirect", "false"))).isNotNull();
        assertThat(SiteSettings.from(with("hitCounterEnabled", "FALSE"))).isNotNull();
        assertThatThrownBy(() -> SiteSettings.from(with("canonicalRedirect", "true")))
                .hasMessageContaining("maccms.site.canonical-redirect");
        assertThatThrownBy(() -> SiteSettings.from(with("hitCounterEnabled", "true")))
                .hasMessageContaining("maccms.site.hit-counter-enabled");
    }

    @Test
    void allErrorsAreReportedTogether() {
        SiteProperties p = new SiteProperties("true", "", "ftp://x/", null, null, null, "javascript:x", "maybe", null,
                "true", "true");
        assertThatThrownBy(() -> SiteSettings.from(p)).hasMessageContainingAll("maccms.site.name",
                "maccms.site.base-url", "maccms.site.logo-url", "maccms.site.status", "maccms.site.canonical-redirect",
                "maccms.site.hit-counter-enabled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abs", "a/../b", "..", "a b", "a?b", "a#b", "a\\b", "中文", "a%2Fb", "a//b", "a/./b",
            "a/.", "./a", "//a"})
    void sitePathsRejectUnsafeOrNonNormalized(String path) {
        SiteSettings s = SiteSettings.from(props("合成站", "http://maccms.local.test/"));
        assertThatThrownBy(() -> s.canonical(path)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.publicPath(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sitePathsAcceptNormalizedForms() {
        SiteSettings s = SiteSettings.from(props("合成站", "http://maccms.local.test/"));
        assertThat(s.canonical("vod/type/id/1.html").href()).isEqualTo("http://maccms.local.test/vod/type/id/1.html");
        assertThat(s.canonical("a/b/").href()).isEqualTo("http://maccms.local.test/a/b/");
        assertThat(s.publicPath("").href()).isEqualTo("/");
    }
}

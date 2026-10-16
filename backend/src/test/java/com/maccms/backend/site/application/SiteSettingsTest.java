package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.content.application.VideoListQuery;
import java.util.List;
import java.util.Map;
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

    /** 由若干个 {@code seg/} 子目录拼出总长恰为 length 的 base-url。 */
    private static String subdirectoryBaseUrl(String prefix, int length) {
        StringBuilder sb = new StringBuilder(prefix);
        while (length - sb.length() >= 63) {
            sb.append("d".repeat(60)).append('/');
        }
        // 余下 2～62 个字符作最后一段，不会出现空段
        sb.append("d".repeat(length - sb.length() - 1)).append('/');
        assertThat(sb).hasSize(length);
        return sb.toString();
    }

    @Test
    void baseUrlLengthBoundaryIsDerivedFromSafeUrlBudget() {
        assertThat(SiteSettings.MAX_BASE_URL_LENGTH).isEqualTo(768);
        assertThat(SiteSettings.MAX_BASE_URL_LENGTH + SitePaths.MAX_LENGTH).isEqualTo(SafeUrl.MAX_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(ints = {199, 200, 201, 512, 767, 768})
    void subdirectoryBaseUrlUpToBudgetStartsAndLinks(int length) {
        String base = subdirectoryBaseUrl("https://maccms.local.test:8443/sub/", length);
        SiteSettings s = SiteSettings.from(props("合成站", base));
        String home = base.substring("https://maccms.local.test:8443".length());
        assertThat(s.baseUrl().href()).isEqualTo(base);
        assertThat(s.homePath().href()).isEqualTo(home);
        String detail = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT).vodDetail(4294967295L);
        assertThat(s.canonical(detail).href()).isEqualTo(base + detail);
        assertThat(s.publicPath(detail).href()).isEqualTo(home + detail);
        assertThat(s.publicPath("site/site.css").href()).isEqualTo(home + "site/site.css");
    }

    @Test
    void longRootBaseUrlWithinBudgetIsAccepted() {
        // 根路径但主机名较长（各标签 ≤ 63），完整地址超过旧的 200 字符上限
        String host = String.join(".", "a".repeat(63), "b".repeat(63), "c".repeat(63), "test");
        SiteSettings s = SiteSettings.from(props("合成站", "https://" + host + ":8443/"));
        assertThat(s.baseUrl().href()).hasSizeGreaterThan(200);
        assertThat(s.homePath().href()).isEqualTo("/");
    }

    @Test
    void baseUrlOverBudgetFailsWithoutEchoingValue() {
        String base = subdirectoryBaseUrl("https://maccms.local.test/" + SECRET + "/", SiteSettings.MAX_BASE_URL_LENGTH + 1);
        assertThatThrownBy(() -> SiteSettings.from(props("合成站", base)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.base-url")
                .hasMessageContaining("最多 768 个字符")
                .hasMessageNotContaining(SECRET);
        // 首尾空白先去掉再计长度
        assertThat(SiteSettings.from(props("合成站", "  " + subdirectoryBaseUrl("https://maccms.local.test/", 768) + " "))
                .baseUrl().href()).hasSize(768);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?q=1/", "#f/", "a//", "../", "./", "%2e%2e/", "%41/", "a\tb/", "a\u0000b/", "a b/", "a\\b/",
            "a\"b/", "a<b/", "a b/", "a b/"})
    void longBaseUrlStillRejectsUnsafeParts(String tail) {
        String base = subdirectoryBaseUrl("https://maccms.local.test/sub/", 300) + tail;
        assertThat(base.length()).isGreaterThan(200).isLessThanOrEqualTo(SiteSettings.MAX_BASE_URL_LENGTH);
        assertThatThrownBy(() -> SiteSettings.from(props("合成站", base)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.base-url")
                .hasMessageNotContaining(base);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://maccms.local.test/", "HTTPS://maccms.local.test/", "https://u:p@maccms.local.test/",
            "https://@maccms.local.test/", "//maccms.local.test/", "https:///", "/sub/"})
    void longBaseUrlStillRejectsSchemeAndAuthority(String prefix) {
        String base = subdirectoryBaseUrl(prefix, 300);
        assertThatThrownBy(() -> SiteSettings.from(props("合成站", base)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.base-url");
    }

    @Test
    void maximumBaseUrlAndMaximumSitePathStayWithinSafeUrl() {
        String base = subdirectoryBaseUrl("https://maccms.local.test/", SiteSettings.MAX_BASE_URL_LENGTH);
        SiteSettings s = SiteSettings.from(props("合成站", base));
        String longest = subdirectoryBaseUrl("", SitePaths.MAX_LENGTH);
        assertThat(s.canonical(longest).href()).hasSize(SafeUrl.MAX_LENGTH);
        assertThat(s.publicPath(longest).href()).hasSizeLessThan(SafeUrl.MAX_LENGTH);
        // 超出站内地址预算的路径按"站内路径不合法"拒绝，与 base-url 长短无关
        String tooLong = subdirectoryBaseUrl("", SitePaths.MAX_LENGTH + 1);
        SiteSettings root = SiteSettings.from(props("合成站", "http://maccms.local.test/"));
        assertThatThrownBy(() -> root.canonical(tooLong)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("站内路径不合法");
        assertThatThrownBy(() -> s.publicPath(tooLong)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("站内路径不合法");
    }

    /**
     * 现有生成器的最长输出（旧搜索页，参数取公开接口的码点上限，全部为 3 字节汉字，页码取上限）在每种 URL 形态下都在
     * 站内地址预算内，拼上最长 base-url 后仍是 SafeUrl。
     */
    @Test
    void longestSearchUrlFitsWithMaximumBaseUrl() {
        String base = subdirectoryBaseUrl("https://maccms.local.test/", SiteSettings.MAX_BASE_URL_LENGTH);
        SiteSettings s = SiteSettings.from(props("合成站", base));
        Map<String, String> filters = Map.of("wd", "汉".repeat(64), "area", "汉".repeat(20), "lang", "汉".repeat(10),
                "class", "汉".repeat(32), "year", "2020", "level", "9", "by", "hits_month", "order", "desc");
        int longest = 0;
        for (LegacyUrlStyle style : List.of(new LegacyUrlStyle(false, false, false, '/'),
                new LegacyUrlStyle(true, false, false, '/'), new LegacyUrlStyle(false, true, false, '/'),
                new LegacyUrlStyle(true, true, false, '/'), new LegacyUrlStyle(false, false, true, '/'),
                new LegacyUrlStyle(true, true, true, '/'), new LegacyUrlStyle(false, false, false, '-'),
                new LegacyUrlStyle(true, true, false, '-'))) {
            String path = new LegacyUrlBuilder(style).vodSearch(filters, VideoListQuery.MAX_PAGE);
            longest = Math.max(longest, path.length());
            assertThat(s.canonical(path).href()).isEqualTo(base + path).hasSizeLessThanOrEqualTo(SafeUrl.MAX_LENGTH);
            assertThat(s.publicPath(path).href()).endsWith(path);
        }
        assertThat(longest).isEqualTo(1235);
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
            "a/.", "./a", "//a", "a%2fb", "a%e4b", "a%2Eb", "%2E%2E/a", "a%5Cb", "a%00b", "a%0Ab", "a%1Fb", "a%7Fb",
            "a%G1", "a%4", "a%", "a:b", "a@b", "a;b"})
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
        // 旧 urlencode 的输出形态（大写 %HH、+）用于链接到旧搜索/筛选页（MACC-10）
        assertThat(s.publicPath("index.php/vod/search/wd/%E5%90%88+a.html").href())
                .isEqualTo("/index.php/vod/search/wd/%E5%90%88+a.html");
        assertThat(s.canonical("index.php/vod/search/wd/...html").href())
                .isEqualTo("http://maccms.local.test/index.php/vod/search/wd/...html");
    }
}

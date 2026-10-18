package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlStyle;
import com.maccms.backend.site.application.SiteProperties;
import com.maccms.backend.site.application.SiteSettings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 页眉 Logo 与 W2（B05 K5 整合，队长对 MACC-54 的修正）：W2 关时外站 Logo 不输出，站内路径与 base-url 同主机的绝对地址照常输出；
 * 根路径与子目录部署相同（"本站"只按 base-url 主机判定）。四种 W1/W2 组合下的真实响应见 {@link PlaybackSwitchCases#logoFollowsW2}，
 * W1 不参与该判定（SitePages 只接收 W2）。
 */
class SitePagesLogoTest {

    private static final LegacyUrlBuilder URLS = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT);

    private static Optional<String> logo(String baseUrl, String logoUrl, boolean w2) {
        SiteSettings s = SiteSettings.from(new SiteProperties("true", "合成站", baseUrl, null, null, null, logoUrl, null,
                null, null, null));
        return new SitePages(s, URLS, w2).notFound().chrome().logo().map(u -> u.href());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://maccms.local.test/", "https://maccms.local.test:8443/sub/"})
    void externalLogoOnlyWithW2AndSiteLogosAlways(String baseUrl) {
        String external = "https://logo.example.invalid/logo.png";
        assertThat(logo(baseUrl, external, true)).contains(external);
        assertThat(logo(baseUrl, external, false)).isEmpty();
        // 外站主机只是以本站主机名开头或结尾时仍是外站
        for (String lookalike : List.of("https://maccms.local.test.example.invalid/l.png",
                "https://cdn-maccms.local.test/l.png", "http://sub.maccms.local.test/l.png")) {
            assertThat(logo(baseUrl, lookalike, false)).as(lookalike).isEmpty();
            assertThat(logo(baseUrl, lookalike, true)).as(lookalike).contains(lookalike);
        }
        for (String local : List.of("/upload/site/logo.png", "/sub/upload/site/logo.png",
                "http://maccms.local.test/upload/logo.png", "https://MACCMS.local.test:8443/sub/logo.png",
                "https://maccms.local.test./logo.png")) {
            assertThat(logo(baseUrl, local, false)).as(local).contains(local);
            assertThat(logo(baseUrl, local, true)).as(local).contains(local);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unsafeOrEmptyLogoIsNeverOutput(boolean w2) {
        assertThat(logo("http://maccms.local.test/", "", w2)).isEmpty();
        // SafeUrl 防护保留：非法地址在启动校验时就被拒绝（SiteSettingsTest），这里只确认空值不输出
        assertThat(logo("http://maccms.local.test/", null, w2)).isEmpty();
    }
}

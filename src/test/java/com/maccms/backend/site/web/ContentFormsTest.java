package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlStyle;
import com.maccms.backend.site.application.SiteProperties;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.HiddenField;
import com.maccms.backend.site.web.view.InteractionPanel;
import org.junit.jupiter.api.Test;

/** 内容详情页的互动区（B11，20 文档第 8 节）：点击计数图片只在 {@code maccms.site.hit-counter-enabled=true} 时输出（03 方案第 6.3 节）。 */
class ContentFormsTest {

    private static final LegacyUrlBuilder URLS = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT);

    private static SiteSettings site(String hitCounter) {
        return SiteSettings.from(new SiteProperties("true", "合成站", "http://maccms.local.test/", "", "", "", "", "", "",
                "", hitCounter));
    }

    @Test
    void hitsBeaconOnlyWhenHitCounterEnabled() {
        assertThat(ContentForms.interaction(site(""), URLS, 2, 7).hitsBeacon()).isEmpty();
        assertThat(ContentForms.interaction(site("false"), URLS, 2, 7).hitsBeacon()).isEmpty();
        InteractionPanel on = ContentForms.interaction(site("true"), URLS, 2, 7);
        assertThat(on.hitsBeacon()).isPresent();
        assertThat(on.hitsBeacon().get().href()).startsWith("/index.php/ajax/hits").contains("mid").contains("2")
                .contains("7").contains("update");
    }

    @Test
    void formsCarryMidAndIdWhateverTheCounterSetting() {
        InteractionPanel p = ContentForms.interaction(site(""), URLS, 2, 7);
        assertThat(p.upForm().fields()).containsExactly(new HiddenField("mid", "2"), new HiddenField("id", "7"),
                new HiddenField("type", "up"));
        assertThat(p.downForm().fields()).contains(new HiddenField("type", "down"));
        assertThat(p.scoreFields()).containsExactly(new HiddenField("mid", "2"), new HiddenField("id", "7"));
        assertThat(p.scoreOptions()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(p.comments()).isPresent();
    }
}

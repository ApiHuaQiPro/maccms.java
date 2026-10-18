package com.maccms.backend.site.web;

import com.maccms.backend.site.config.SiteConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;

/** W1=true、W2=false 时的播放/下载页与 playerconfig.js（{@link PlaybackSwitchCases}）。 */
@WebMvcTest(controllers = {SitePlaybackController.class, PlayerConfigController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.logo-url=" + PlaybackSwitchCases.LOGO,
        "maccms.legacy-config.dir=" + PlaybackFixtures.LEGACY_DIR,
        "maccms.browser-external.w1=true",
        "maccms.browser-external.w2=false"})
@Import(SiteConfiguration.class)
class SitePlaybackSwitch10MvcTest extends PlaybackSwitchCases {

    @Override
    boolean w1() {
        return true;
    }

    @Override
    boolean w2() {
        return false;
    }
}

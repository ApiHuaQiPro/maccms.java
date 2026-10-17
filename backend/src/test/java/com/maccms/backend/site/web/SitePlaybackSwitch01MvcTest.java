package com.maccms.backend.site.web;

import com.maccms.backend.site.config.SiteConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;

/** W1=false、W2=true 时的播放/下载页与 playerconfig.js（{@link PlaybackSwitchCases}）。 */
@WebMvcTest(controllers = {SitePlaybackController.class, PlayerConfigController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.logo-url=" + PlaybackSwitchCases.LOGO,
        "maccms.legacy-config.dir=" + PlaybackFixtures.LEGACY_DIR,
        "maccms.browser-external.w1=false",
        "maccms.browser-external.w2=true"})
@Import(SiteConfiguration.class)
class SitePlaybackSwitch01MvcTest extends PlaybackSwitchCases {

    @Override
    boolean w1() {
        return false;
    }

    @Override
    boolean w2() {
        return true;
    }
}

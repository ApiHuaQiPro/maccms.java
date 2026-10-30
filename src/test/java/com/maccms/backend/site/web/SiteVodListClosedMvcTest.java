package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** 旧 app.show、app.ajax_page 按仓库值关闭（默认）时：vod/show、vodshow、ajax_show、ajax_search 都是 404，不查库。 */
@WebMvcTest(controllers = {SiteSearchController.class, SiteVodShowController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/"})
@Import(SiteConfiguration.class)
class SiteVodListClosedMvcTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    @Test
    void showAndAjaxEntriesAre404WhenTheLegacySwitchesAreOff() throws Exception {
        for (String path : List.of("/index.php/vod/show/id/1.html", "/index.php/vodshow/1-----------.html",
                "/index.php/vod/ajax_show/id/1.html", "/index.php/vod/ajax_search.html")) {
            int status = mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isEqualTo(404);
        }
        verifyNoInteractions(content);
    }
}

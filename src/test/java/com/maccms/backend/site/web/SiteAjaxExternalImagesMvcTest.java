package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * W2 关闭（maccms.browser-external.w2=false，路线图第 4.2 节）时 ajax/data 的外链图片为空串，本站图片照常（16 文档第 4.10 节）。
 */
@WebMvcTest(controllers = SiteAjaxController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.browser-external.w2=false"})
@Import(SiteConfiguration.class)
class SiteAjaxExternalImagesMvcTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    LegacyRowReadService rows;
    @MockitoBean
    ContentReadService content;

    @Test
    void externalImagesAreBlankWhenW2IsOff() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actor_id", 1L);
        m.put("type_id", 0L);
        m.put("actor_time", 1L);
        m.put("actor_time_add", 1L);
        m.put("actor_pic", "mac://img.example.test/a.jpg");
        Map<String, Object> local = new LinkedHashMap<>(m);
        local.put("actor_id", 2L);
        local.put("actor_pic", "http://maccms.local.test/upload/a.jpg");
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(2, List.of(new LegacyRowDto(m), new LegacyRowDto(local))));
        String json = mvc.perform(get(URI.create("/index.php/ajax/data?mid=8"))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"actor_pic\":\"\",").doesNotContain("img.example.test")
                .contains("\"actor_pic\":\"http:\\/\\/maccms.local.test\\/upload\\/a.jpg\"");
    }
}

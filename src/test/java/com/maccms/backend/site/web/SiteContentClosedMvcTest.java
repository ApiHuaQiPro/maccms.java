package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 站点关闭时三页（含非法参数的地址）都返回 503 维护页（Retry-After、no-store、noindex），不调用 content 服务。 */
@WebMvcTest(controllers = SiteContentController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.status=closed"})
@Import(SiteConfiguration.class)
class SiteContentClosedMvcTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.php", "/index.php/vod/type/id/1.html", "/index.php/vod/type/id/1/page/2.html",
            "/index.php/vod/detail/id/9.html", "/index.php/vod/type/id/0.html", "/index.php/vod/detail/id/abc.html",
            "/?from=x"})
    void contentPagesReturnMaintenanceWithoutQueries(String path) throws Exception {
        MvcResult r = mvc.perform(get(path)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("3600");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        assertThat(r.getResponse().getHeader("Content-Length"))
                .isEqualTo(Integer.toString(r.getResponse().getContentAsByteArray().length));
        assertThat(body.stripTrailing()).startsWith("<!DOCTYPE html>").endsWith("</html>");
        Document doc = Jsoup.parse(body);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("站点维护中");
        assertThat(doc.select("meta[name=robots]").attr("content")).isEqualTo("noindex");
        assertThat(doc.select("link[rel=canonical], .site-nav")).isEmpty();
        verifyNoInteractions(content);
    }
}

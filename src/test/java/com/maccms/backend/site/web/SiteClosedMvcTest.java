package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.api.VideoCategoryController;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * maccms.site.status=closed：所有 site 页面返回 503 维护页（Retry-After、no-store、noindex），
 * 页面 Controller 不执行（因此不会读取任何数据）；content API 不受影响。维护文案按文本转义。
 */
@WebMvcTest(controllers = {SitePlaceholderController.class, SiteTestRoutes.class, VideoCategoryController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.site.status=closed",
                "maccms.site.closed-message=维护中 <b>升级</b> & \"稍后\""})
@Import({SiteConfiguration.class, SiteTestRoutes.class})
class SiteClosedMvcTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService contentReadService;

    @BeforeEach
    void reset() {
        SiteTestRoutes.INVOCATIONS.set(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_site/placeholder", "/__site-test/fail", "/__site-test/boom-large",
            "/__site-test/api-error/VIDEO_NOT_FOUND", "/__site-test/item/1"})
    void everySitePageReturnsMaintenance503WithoutRunningController(String path) throws Exception {
        MvcResult r = mvc.perform(get(path)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("3600");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        assertThat(r.getResponse().getContentType()).isEqualToIgnoringCase("text/html;charset=UTF-8");
        assertThat(r.getResponse().getHeader("Content-Length"))
                .isEqualTo(Integer.toString(r.getResponse().getContentAsByteArray().length));
        assertThat(body.stripTrailing()).startsWith("<!DOCTYPE html>").endsWith("</html>");

        Document doc = Jsoup.parse(body);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("站点维护中");
        assertThat(doc.select("[data-site-page=error] p").first().text()).isEqualTo("维护中 <b>升级</b> & \"稍后\"");
        assertThat(doc.select("b")).isEmpty();
        assertThat(doc.select("meta[name=robots]").attr("content")).isEqualTo("noindex");
        assertThat(doc.select("link[rel=canonical]")).isEmpty();

        assertThat(SiteTestRoutes.INVOCATIONS.get()).as("页面 Controller 不应执行").isZero();
        verifyNoInteractions(contentReadService);
    }

    @Test
    void contentApiIsNotAffectedBySiteStatus() throws Exception {
        when(contentReadService.listCategories()).thenReturn(new CategoryListResponse(List.of()));
        MvcResult r = mvc.perform(get("/api/v1/video-categories")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"items\":[]}");
    }
}

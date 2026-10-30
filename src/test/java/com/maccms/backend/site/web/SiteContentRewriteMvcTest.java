package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.categories;
import static com.maccms.backend.site.web.SiteContentFixtures.normalDetail;
import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summaries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * rewrite.status=1、suffix_hide=1、站点装在子目录时：链接与 canonical 都按生成器输出（不含 index.php、.html 换成 /），
 * 带 index.php 的旧形态仍能识别并给出同一个 canonical。base-url 子目录只影响对外地址（05 文档第 2 节）。
 */
@WebMvcTest(controllers = SiteContentController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=https://www.maccms.test/sub/",
        "maccms.site.url.rewrite=TRUE",
        "maccms.site.url.suffix-hide=true"})
@Import(SiteConfiguration.class)
class SiteContentRewriteMvcTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void data() {
        when(content.listCategories()).thenReturn(categories());
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            return page(summaries(500, 20, ref(6, "动作片", 1)), q.page(), q.pageSize(), 60);
        });
        when(content.getVideo(9)).thenReturn(normalDetail());
    }

    private Document ok(String path) throws Exception {
        MvcResult r = mvc.perform(get(path)).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource({
            "/, https://www.maccms.test/sub/",
            "/index.php, https://www.maccms.test/sub/",
            "/vod/type/id/1/, https://www.maccms.test/sub/vod/type/id/1/",
            "/vod/type/id/1.html, https://www.maccms.test/sub/vod/type/id/1/",
            "/index.php/vod/type/id/1.html, https://www.maccms.test/sub/vod/type/id/1/",
            "/vod/type/id/1/page/2/, https://www.maccms.test/sub/vod/type/id/1/page/2/",
            "/vod/detail/id/9/, https://www.maccms.test/sub/vod/detail/id/9/",
            "/index.php/vod/detail/id/9.html, https://www.maccms.test/sub/vod/detail/id/9/"})
    void canonicalFollowsConfiguredStyle(String path, String canonical) throws Exception {
        assertThat(ok(path).selectFirst("link[rel=canonical]").attr("href")).isEqualTo(canonical);
    }

    @Test
    void linksUseConfiguredStyleAndSubdirectory() throws Exception {
        Document doc = ok("/vod/type/id/1/");
        assertThat(doc.select(".site-nav a").first().attr("href")).isEqualTo("/sub/vod/type/id/1/");
        assertThat(doc.selectFirst("a.pagination__link--next").attr("href")).isEqualTo("/sub/vod/type/id/1/page/2/");
        assertThat(doc.selectFirst("a.video-card__link").attr("href")).isEqualTo("/sub/vod/detail/id/500/");
        assertThat(doc.selectFirst("a.site-brand").attr("href")).isEqualTo("/sub/");
        assertThat(doc.selectFirst("form.site-search").attr("action")).isEqualTo("/sub/vod/search/");
        assertThat(doc.selectFirst("a.site-login").attr("href")).isEqualTo("/sub/user/login/");
        assertThat(doc.selectFirst("img.video-card__image").attr("src")).isEqualTo("/sub/upload/vod/500.jpg");
    }

    @Test
    void unsupportedAliasesAreNotRouted() throws Exception {
        for (String path : new String[] {"/index.php/vodtype/1.html", "/vodtype/1.html", "/vod-type-id-1.html",
                "/index.php/vod/detail/id/9.html?x=1"}) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(404);
        }
        verifyNoInteractions(content);
    }
}

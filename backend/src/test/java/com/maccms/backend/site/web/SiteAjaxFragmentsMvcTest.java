package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 打开 app.ajax_page（{@code maccms.site.features.ajax-page=true}）时的 ajax_detail（B05，D-B05-5）：与详情页相同视图与过滤的 HTML 片段，
 * 没有外壳、没有脚本，状态码与详情页相同。
 */
@WebMvcTest(controllers = SiteLibraryController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.features.ajax-page=true"})
@Import(SiteConfiguration.class)
class SiteAjaxFragmentsMvcTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ContentReadService content;
    @MockitoBean
    ArticleReadService articles;
    @MockitoBean
    TopicReadService topics;
    @MockitoBean
    ActorReadService actors;
    @MockitoBean
    RoleReadService roles;
    @MockitoBean
    WebsiteReadService websites;
    @MockitoBean
    VideoPlotReadService plots;

    @BeforeEach
    void setUp() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
        when(articles.listCategories()).thenReturn(LibraryPageFixtures.artTypes());
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.normalDetail());
        when(articles.getArticle(eq(55L), anyInt(), any())).thenReturn(LibraryPageFixtures.article(55, 3));
        when(topics.getTopic(7)).thenReturn(LibraryPageFixtures.topic(7));
        when(content.listVideos(any())).thenReturn(SiteContentFixtures.page(java.util.List.of(), 1, 20, 0));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    @Test
    void fragmentsHaveNoShellAndNoScripts() throws Exception {
        for (String[] c : new String[][] {{"/index.php/vod/ajax_detail/id/9.html", "article.video-detail"},
                {"/index.php/art/ajax_detail/id/55.html", "article.article-page"},
                {"/index.php/topic/ajax_detail/id/7.html", "article.topic-page"}}) {
            MvcResult r = fetch(c[0]);
            assertThat(r.getResponse().getStatus()).as(c[0]).isEqualTo(200);
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).as(c[0]).doesNotContain("<html", "<head", "<body", "site-header", "<script");
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).isEqualTo(SiteHtmlRenderer.CONTENT_SECURITY_POLICY);
            assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
            Document doc = Jsoup.parseBodyFragment(body);
            assertThat(doc.select(c[1])).as(c[0]).hasSize(1);
            assertThat(doc.select(".detail-content script")).isEmpty();
        }
        // 文章片段是第 1 页
        assertThat(fetch("/index.php/art/ajax_detail/id/55.html").getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("PAGE-1-BODY").doesNotContain("PAGE-2-BODY");
    }

    @Test
    void restrictedContentHasTheSameStatusAsTheDetailPageWithoutAForm() throws Exception {
        when(articles.getArticle(eq(60L), anyInt(), any())).thenThrow(new ApiException(ErrorCode.ARTICLE_PASSWORD_REQUIRED, "x"));
        assertThat(fetch("/index.php/art/ajax_detail/id/60.html").getResponse().getStatus()).isEqualTo(404);
        when(content.getVideo(10)).thenThrow(new ApiException(ErrorCode.VIDEO_NOT_FOUND, "x"));
        assertThat(fetch("/index.php/vod/ajax_detail/id/10.html").getResponse().getStatus()).isEqualTo(404);
    }
}

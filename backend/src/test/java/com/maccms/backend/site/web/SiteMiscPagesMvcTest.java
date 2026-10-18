package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.site.config.SiteConfiguration;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.w3c.dom.NodeList;

/**
 * B06 杂项页（16 文档第 4.1–4.5 节）的 MockMvc 测试：地图、帮助、版权提示、站点级 RSS 与搜索引擎地图、自定义标签页，以及
 * 旧 URL 形态（单段控制器名、.xml 后缀、兼容模式 ?s=）。content 服务是替身。
 */
@WebMvcTest(controllers = {SiteMiscController.class, SiteAjaxController.class, SiteContentController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站 & <b>",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.site.description=站点描述"})
@Import(SiteConfiguration.class)
class SiteMiscPagesMvcTest {

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
    @MockitoBean
    LegacyRowReadService rows;

    @BeforeEach
    void setUp() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
        when(articles.listCategories()).thenReturn(LibraryPageFixtures.artTypes());
        when(actors.listCategories()).thenReturn(LibraryPageFixtures.actorTypes());
        when(websites.listCategories()).thenReturn(LibraryPageFixtures.websiteTypes());
        var cat = SiteContentFixtures.ref(6, "动作片", 1);
        var items = new java.util.ArrayList<>(SiteContentFixtures.summaries(1, 2, cat));
        items.add(SiteContentFixtures.summary(3, "A & B <c> \u0001", "", cat));
        when(content.listVideos(any())).thenReturn(SiteContentFixtures.page(items, 1, 50, 3));
        when(content.getVideo(9L)).thenReturn(SiteContentFixtures.normalDetail());
        when(content.getVideo(404L)).thenThrow(new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在"));
        when(content.getVideo(10L)).thenThrow(new ApiException(ErrorCode.VIDEO_PASSWORD_REQUIRED, "需要密码"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vod_id", 7L);
        r.put("vod_time", 1700000000L);
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(1, List.of(new LegacyRowDto(r))));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(URI.create(path))).andReturn();
    }

    private static Document html(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static org.w3c.dom.Document xml(MvcResult r) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
    }

    @Test
    void mapListsVisibleCategoriesAndEntryLinks() throws Exception {
        MvcResult r = fetch("/index.php/map/index.html");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'none'");
        Document d = html(r);
        assertThat(d.select("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test/index.php/map/index.html");
        assertThat(d.select("section.map-page h1").text()).isEqualTo("网站地图");
        assertThat(d.select("section.map-section h2#map-vod").text()).isEqualTo("视频分类");
        assertThat(d.select("section.map-section[aria-labelledby=map-vod] ul.map-tree > li.map-tree__item > a").first()
                .attr("href")).startsWith("/index.php/vod/type/id/");
        assertThat(d.select("section.map-section[aria-labelledby=map-art] a").first().attr("href"))
                .startsWith("/index.php/art/type/id/");
        assertThat(d.select("ul.map-links a").eachAttr("href")).contains("/index.php/topic/index.html",
                "/index.php/rss/index.xml", "/index.php/rss/baidu.xml", "/index.php/rss/sm.xml");
        assertThat(d.select("script")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/map", "/index.php/map/", "/index.php/map.html", "/index.php/map/index",
            "/index.php/map/index/"})
    void mapShorthandFormsAreInputsWithTheSameCanonical(String path) throws Exception {
        MvcResult r = fetch(path);
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        assertThat(html(r).select("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/map/index.html");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.php/map/index.html?x=1", "/index.php/map/index/id/1.html", "/index.php/map/index.xml",
            "/index.php/help/index.html?wd=x", "/index.php/rss/index.xml?page=2", "/index.php/rss/other.xml",
            "/map/index.html", "/index.php/vod/copyright.html", "/index.php/vod/copyright/id/0.html"})
    void parametersAndUnknownFormsAre404(String path) throws Exception {
        assertThat(fetch(path).getResponse().getStatus()).as(path).isEqualTo(404);
    }

    @Test
    void helpHasFixedStructureWithoutEchoingParameters() throws Exception {
        MvcResult r = fetch("/index.php/help/index.html");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = html(r);
        assertThat(d.select("section.help-page h1").text()).isEqualTo("帮助");
        assertThat(d.select("section.help-page .detail-content").text()).contains("合成站 & <b>");
        assertThat(d.select("section.help-page a").eachAttr("href")).contains("/index.php/vod/search.html",
                "/index.php/map/index.html", "/index.php/rss/index.xml");
    }

    @Test
    void copyrightPageShowsTheNoticeAndHidesRestrictedDetails() throws Exception {
        MvcResult r = fetch("/index.php/vod/copyright/id/9.html");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        Document d = html(r);
        assertThat(d.select("section.copyright-page h1").text()).startsWith("流浪地球");
        assertThat(d.select("p.notice.copyright-notice").text()).isEqualTo("该视频由于版权限制，暂不提供播放。");
        assertThat(d.html()).doesNotContain("<script>alert");
        // 需要访问密码的视频：仍显示提示页，但不输出视频名
        Document locked = html(fetch("/index.php/vod/copyright/id/10.html"));
        assertThat(locked.select("section.copyright-page h1").text()).isEqualTo("版权提示");
        assertThat(fetch("/index.php/vod/copyright/id/404.html").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void rssIsWellFormedAndEscaped() throws Exception {
        MvcResult r = fetch("/index.php/rss/index.xml");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/rss+xml;charset=UTF-8");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        org.w3c.dom.Document d = xml(r);
        NodeList items = d.getElementsByTagName("item");
        assertThat(items.getLength()).isEqualTo(3);
        assertThat(d.getElementsByTagName("title").item(3).getTextContent()).isEqualTo("A & B <c> ");
        assertThat(d.getElementsByTagName("link").item(1).getTextContent())
                .isEqualTo("http://maccms.local.test/index.php/vod/detail/id/1.html");
        ArgumentCaptor<VideoListQuery> q = ArgumentCaptor.forClass(VideoListQuery.class);
        org.mockito.Mockito.verify(content).listVideos(q.capture());
        assertThat(q.getValue().pageSize()).isEqualTo(50);
        // 非规范的 .html 形态与单段 rss 照常输出
        assertThat(fetch("/index.php/rss/index.html").getResponse().getStatus()).isEqualTo(200);
        assertThat(fetch("/index.php/rss.xml").getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"baidu", "google", "so", "sogou", "bing", "sm"})
    void sitemapsUseTheSitemapProtocol(String format) throws Exception {
        MvcResult r = fetch("/index.php/rss/" + format + ".xml");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/xml;charset=UTF-8");
        org.w3c.dom.Document d = xml(r);
        assertThat(d.getDocumentElement().getLocalName()).isEqualTo("urlset");
        assertThat(d.getDocumentElement().getNamespaceURI()).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        NodeList locs = d.getElementsByTagNameNS("*", "loc");
        assertThat(locs.getLength()).isEqualTo(2);
        assertThat(locs.item(0).getTextContent()).isEqualTo("http://maccms.local.test/");
        assertThat(locs.item(1).getTextContent()).isEqualTo("http://maccms.local.test/index.php/vod/detail/id/7.html");
        assertThat(d.getElementsByTagNameNS("*", "lastmod").item(0).getTextContent()).isEqualTo("2023-11-15");
    }

    @Test
    void labelPagesAreAlwaysIllegalRequests() throws Exception {
        for (String p : List.of("/index.php/label/notice.html", "/index.php/label/index.html?file=../../x",
                "/index.php/label/abc/file/def.html")) {
            MvcResult r = fetch(p);
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(404);
            assertThat(html(r).select("h1").text()).isEqualTo("非法请求");
            assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    void compatibilityModeOnTheEntryPath() throws Exception {
        MvcResult r = fetch("/index.php?s=map/index");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(html(r).select("link[rel=canonical]").attr("href"))
                .isEqualTo("http://maccms.local.test/index.php/map/index.html");
        assertThat(fetch("/index.php?s=rss/baidu.xml").getResponse().getContentType())
                .isEqualTo("application/xml;charset=UTF-8");
    }
}

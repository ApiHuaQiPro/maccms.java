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
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.site.application.StaticPageSource;
import com.maccms.backend.site.application.StaticPageSource.Target;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * B16 静态生成的页面来源（25 文档第 4 节）：进程内渲染与浏览器访问同一动态地址的正文逐字节相同；外层请求的属性、cookie 与会话不受影响；
 * 页面不存在时报状态、不给正文。content 服务是替身（同 {@link SiteMiscPagesMvcTest}）。
 */
@WebMvcTest(controllers = {SiteMiscController.class, SiteAjaxController.class, SiteContentController.class,
        SiteLibraryController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站 & <b>",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.site.description=站点描述"})
@Import(SiteConfiguration.class)
class InternalPageRendererTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    StaticPageSource source;
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

    MockHttpServletRequest outer;

    @BeforeEach
    void setUp() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
        when(articles.listCategories()).thenReturn(LibraryPageFixtures.artTypes());
        when(actors.listCategories()).thenReturn(LibraryPageFixtures.actorTypes());
        when(websites.listCategories()).thenReturn(LibraryPageFixtures.websiteTypes());
        var cat = SiteContentFixtures.ref(6, "动作片", 1);
        var items = new java.util.ArrayList<>(SiteContentFixtures.summaries(1, 2, cat));
        when(content.listVideos(any())).thenReturn(SiteContentFixtures.page(items, 1, 50, 2));
        when(content.getVideo(9L)).thenReturn(SiteContentFixtures.normalDetail());
        when(content.getVideo(404L)).thenThrow(new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vod_id", 7L);
        r.put("vod_time", 1700000000L);
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(1, List.of(new LegacyRowDto(r))));

        // 外层请求（后台或定时任务入口）：带 cookie、会话与自己的属性，生成过程不能读到或改动它们
        outer = new MockHttpServletRequest("POST", "/_admin.php/admin/make/make");
        outer.setCookies(new jakarta.servlet.http.Cookie("PHPSESSID", "outer"));
        outer.getSession(true).setAttribute("admin", "x");
        outer.setAttribute("outer.attr", "keep");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(outer, new MockHttpServletResponse()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private String browser(String path) throws Exception {
        MvcResult r = mvc.perform(get(URI.create(path))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void same(Target t, String path) throws Exception {
        String expected = browser(path);
        StaticPageSource.Rendered got = source.render(t);
        assertThat(got.status()).as(path).isEqualTo(200);
        assertThat(new String(got.body(), java.nio.charset.StandardCharsets.UTF_8)).as(path).isEqualTo(expected);
    }

    @Test
    void renderedBodiesEqualBrowserResponses() throws Exception {
        same(new StaticPageSource.Home(false), "/");
        same(new StaticPageSource.VodType(6, 1), "/index.php/vod/type/id/6.html");
        same(new StaticPageSource.VodDetail(9), "/index.php/vod/detail/id/9.html");
        same(new StaticPageSource.Sitemap(), "/index.php/map/index.html");
        same(new StaticPageSource.Rss("baidu"), "/index.php/rss/baidu.xml");
        same(new StaticPageSource.Rss("index"), "/index.php/rss/index.xml");
    }

    @Test
    void outerRequestIsUntouchedAndRestored() {
        source.render(new StaticPageSource.Home(false));
        assertThat(RequestContextHolder.getRequestAttributes()).isInstanceOfSatisfying(ServletRequestAttributes.class,
                a -> assertThat(a.getRequest()).isSameAs(outer));
        assertThat(outer.getAttribute("outer.attr")).isEqualTo("keep");
        assertThat(java.util.Collections.list(outer.getAttributeNames())).containsExactly("outer.attr");
        assertThat(outer.getSession(false).getAttribute("admin")).isEqualTo("x");
    }

    @Test
    void missingPagesReportStatusWithoutBody() {
        StaticPageSource.Rendered missing = source.render(new StaticPageSource.VodDetail(404));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).isEmpty();
        // 自定义页：当前没有内置标签页（16 文档第 4.3 节），一律"非法请求"
        assertThat(source.render(new StaticPageSource.Label("about")).ok()).isFalse();
        assertThat(source.render(new StaticPageSource.Label("../x")).status()).isEqualTo(404);
    }
}

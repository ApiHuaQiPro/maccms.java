package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.LEAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.application.ContentProperties;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 搜索页经真实 ContentReadService 遇到 MySQL 服务端执行时限（3024；排序阶段中止为 1028，MACC-22）时返回 503 页：
 * 只有 mapper 是 Mockito 替身（合成数据，不连数据库）。错误页与日志不含错误 message、SQL 与筛选值。
 */
@WebMvcTest(controllers = SiteSearchController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/"})
@Import({SiteConfiguration.class, SiteSearchStatementTimeoutMvcTest.RealContentService.class})
@ExtendWith(OutputCaptureExtension.class)
class SiteSearchStatementTimeoutMvcTest {

    private static final String AREA = "合成地区LEAK7f3";

    @TestConfiguration
    static class RealContentService {
        @Bean
        ContentReadService contentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper) {
            return new ContentReadService(typeMapper, groupMapper, vodMapper, new ContentProperties(true, true));
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TypeMapper typeMapper;

    @MockitoBean
    GroupMapper groupMapper;

    @MockitoBean
    VodMapper vodMapper;

    @BeforeEach
    void data() {
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(groupMapper.selectGroupPermission(anyInt())).thenReturn(Fixtures.guestGroup());
    }

    @ParameterizedTest
    @ValueSource(ints = {3024, 1028})
    void serverStatementTimeoutIs503Page(int code, CapturedOutput output) throws Exception {
        reset(vodMapper);
        when(vodMapper.countPublishedMatching(any(), any())).thenThrow(new UncategorizedSQLException(LEAK,
                "SELECT " + LEAK, new SQLException(LEAK, "HY000", code)));
        String query = "area=" + URLEncoder.encode(AREA, StandardCharsets.UTF_8);
        MvcResult r = mvc.perform(SiteSearchPagesMvcTest.raw("/index.php/vod/search.html?" + query)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(503);
        SiteContentPagesMvcTest.assertHtml(r);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("120");
        Document doc = Jsoup.parse(body);
        assertThat(doc.select("[data-site-page=error]")).hasSize(1);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("服务暂时不可用");
        assertThat(body).doesNotContain(LEAK).doesNotContain(AREA).doesNotContain("SQLException");
        assertThat(output.getAll()).contains("database statement timeout").contains("site page unavailable")
                .doesNotContain(LEAK).doesNotContain(AREA).doesNotContain(query);
    }
}

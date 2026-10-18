package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.common.session.MutableClock;
import com.maccms.backend.common.session.SessionStore;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LibrarySearchService;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import jakarta.servlet.http.Cookie;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * B08 的站点接线（17 文档第 2.3–2.5 节）：本地验证码 {@code verify/index}、{@code verify/check} 与搜索频率限制
 * （旧 {@code search_timespan}，仓库值 3 秒）。旧配置目录指向仓库的 {@code application/}（{@code extra/captcha.php}、
 * {@code extra/maccms.php} 的仓库值）；会话时钟可调；验证码随机源固定为 0（码表首字符，验证码恒为 {@code 1111}）。
 */
@WebMvcTest(controllers = {SiteVerifyController.class, SiteSearchController.class, SiteModuleListController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.search.enabled=true",
                "maccms.site.features.ajax-page=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.legacy-config.dir=../application"})
@Import({SiteConfiguration.class, SiteVerifyThrottleMvcTest.TestBeans.class})
class SiteVerifyThrottleMvcTest {

    static final long T0 = 2_000_000_000L;
    static final String SEARCH = "/index.php/vod/search.html?wd=abc";

    @TestConfiguration
    static class TestBeans {
        static final MutableClock CLOCK = new MutableClock(T0);

        @Bean
        @Primary
        SessionStore testSessionStore() {
            return new SessionStore(CLOCK, Duration.ofMinutes(30), 1000);
        }

        @Bean
        @Primary
        RandomGenerator zeroRandom() {
            return () -> 0L;
        }
    }

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
    LibrarySearchService search;

    @BeforeEach
    void data() {
        when(content.searchEnabled()).thenReturn(true);
        when(content.listCategories()).thenReturn(SiteSearchFixtures.categories());
        when(content.listVideos(any())).thenAnswer(inv -> SiteSearchFixtures.answer(inv.getArgument(0)));
    }

    // ------------------------------------------------------------------ 验证码

    @Test
    void imageIsPngWithSessionCookieAndRepoCaptchaSize() throws Exception {
        MvcResult r = mvc.perform(get("/index.php/verify/index.html")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).matches("PHPSESSID=[0-9a-f]{32}; Path=/; HttpOnly; SameSite=Lax");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        assertThat(img.getWidth()).isEqualTo(128);
        assertThat(img.getHeight()).isEqualTo(40);
    }

    @Test
    void checkPassesOnceThenReplayFails() throws Exception {
        Cookie session = newCaptcha(null);
        assertThat(check(session, "1111")).isEqualTo("1");
        assertThat(check(session, "1111")).as("重放").isEqualTo("0");
        // 猜错即作废（D-B08-4），之后正确的码也不通过
        newCaptcha(session);
        assertThat(check(session, "2222")).isEqualTo("0");
        assertThat(check(session, "1111")).isEqualTo("0");
        // 空值与 0 不消耗
        newCaptcha(session);
        assertThat(check(session, "")).isEqualTo("0");
        assertThat(check(session, "0")).isEqualTo("0");
        MvcResult r = mvc.perform(post("/index.php/verify/check").cookie(session)
                .contentType("application/x-www-form-urlencoded").content("verify=1111")).andReturn();
        assertThat(r.getResponse().getContentAsString()).isEqualTo("1");
        assertThat(r.getResponse().getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void expiresAfter1800Seconds() throws Exception {
        Cookie session = newCaptcha(null);
        TestBeans.CLOCK.advanceSeconds(1801);
        try {
            assertThat(check(session, "1111")).isEqualTo("0");
        } finally {
            TestBeans.CLOCK.advanceSeconds(-1801);
        }
    }

    @Test
    void unknownOrMissingSessionNeverValidatesAndIsNotAdopted() throws Exception {
        assertThat(check(null, "1111")).isEqualTo("0");
        Cookie forged = new Cookie("PHPSESSID", "0123456789abcdef0123456789abcdef");
        MvcResult r = mvc.perform(get("/index.php/verify/index").cookie(forged)).andReturn();
        String issued = r.getResponse().getHeader("Set-Cookie");
        assertThat(issued).startsWith("PHPSESSID=").doesNotContain(forged.getValue());
        assertThat(check(forged, "1111")).isEqualTo("0");
    }

    @Test
    void slotsAreSeparateAndBadIdIs404() throws Exception {
        Cookie session = sessionOf(mvc.perform(get("/index.php/verify/index?id=a")).andReturn());
        assertThat(check(session, "1111")).as("默认槽没有验证码").isEqualTo("0");
        MvcResult r = mvc.perform(get(URI.create("/index.php/verify/check?verify=1111&id=a")).cookie(session))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).isEqualTo("1");
        assertThat(mvc.perform(get(URI.create("/index.php/verify/index?id=..%2Fx"))).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 搜索频率

    @Test
    void secondFirstPageSearchWithinTimespanIs429AndDoesNotQuery() throws Exception {
        MvcResult first = mvc.perform(raw(SEARCH)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        Cookie session = sessionOf(first);
        clearInvocations(content);

        MvcResult second = mvc.perform(raw(SEARCH).cookie(session)).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        assertThat(second.getResponse().getHeader("Retry-After")).isEqualTo("3");
        assertThat(second.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("请不要频繁操作，搜索时间间隔为3秒前").contains("data-site-page=\"error\"");
        assertThat(second.getResponse().getHeader("Set-Cookie")).as("已有会话不再签发").isNull();
        verify(content, times(0)).listVideos(any());

        // 第 2 页不计时
        assertThat(mvc.perform(raw("/index.php/vod/search.html?wd=abc&page=2").cookie(session)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        // ajax_search 第 1 页同样计时
        assertThat(mvc.perform(raw("/index.php/vod/ajax_search.html?wd=abc").cookie(session)).andReturn()
                .getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void rejectedRequestsRefreshTheTimerAndQuietWindowRecovers() throws Exception {
        Cookie session = sessionOf(mvc.perform(raw(SEARCH)).andReturn());
        TestBeans.CLOCK.advanceSeconds(2);
        assertThat(status(raw(SEARCH).cookie(session))).isEqualTo(429);
        TestBeans.CLOCK.advanceSeconds(2);
        assertThat(status(raw(SEARCH).cookie(session))).as("距上次（被拒）只有 2 秒").isEqualTo(429);
        TestBeans.CLOCK.advanceSeconds(3);
        assertThat(status(raw(SEARCH).cookie(session))).as("停下 3 秒后恢复").isEqualTo(200);
    }

    @Test
    void clientsWithoutCookiesAreNotLimitedLikeLegacy() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(status(raw(SEARCH))).isEqualTo(200);
        }
    }

    @Test
    void moduleSearchIsLimitedToo() throws Exception {
        Cookie session = sessionOf(mvc.perform(raw("/index.php/art/search.html?wd=x")).andReturn());
        clearInvocations(articles, search);
        assertThat(status(raw("/index.php/art/search.html?wd=x").cookie(session))).isEqualTo(429);
        assertThat(status(raw("/index.php/topic/ajax_search.html?wd=x").cookie(session))).isEqualTo(429);
    }

    @Test
    void concurrentFirstPageSearchesInOneSessionLetExactlyOneThrough() throws Exception {
        Cookie session = sessionOf(mvc.perform(get("/index.php/verify/index")).andReturn());
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return status(raw(SEARCH).cookie(session));
                }));
            }
            go.countDown();
            int ok = 0;
            int limited = 0;
            for (Future<Integer> f : results) {
                int s = f.get();
                if (s == 200) {
                    ok++;
                } else if (s == 429) {
                    limited++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(limited).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 辅助

    private Cookie newCaptcha(Cookie session) throws Exception {
        MockHttpServletRequestBuilder req = get("/index.php/verify/index");
        if (session != null) {
            req = req.cookie(session);
        }
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return session != null ? session : sessionOf(r);
    }

    private String check(Cookie session, String code) throws Exception {
        MockHttpServletRequestBuilder req = get(URI.create("/index.php/verify/check?verify=" + code));
        if (session != null) {
            req = req.cookie(session);
        }
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getContentAsString();
    }

    private int status(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private static Cookie sessionOf(MvcResult r) {
        String h = r.getResponse().getHeader("Set-Cookie");
        assertThat(h).startsWith("PHPSESSID=");
        return new Cookie("PHPSESSID", h.substring("PHPSESSID=".length(), h.indexOf(';')));
    }

    private static MockHttpServletRequestBuilder raw(String pathAndQuery) {
        return SiteSearchPagesMvcTest.raw(pathAndQuery);
    }
}

package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.common.captcha.CaptchaSettings;
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
import com.maccms.backend.member.MemberGolden;
import com.maccms.backend.site.config.SiteConfiguration;
import jakarta.servlet.http.Cookie;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import javax.imageio.ImageIO;
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
import tools.jackson.databind.JsonNode;

/**
 * 中文验证码经过控制器的生成、校验、过期与重放（B08，17 文档第 2.4 节）：旧 {@code extra/captcha.php} 设 {@code useZh=true}
 * （其余为仓库值）。随机源固定为 0，所以验证码恒为 think-captcha 默认 zhSet 的首字重复 4 次（{@code 们们们们}）。
 */
@WebMvcTest(controllers = SiteVerifyController.class,
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.legacy-config.dir=../application"})
@Import({SiteConfiguration.class, SiteVerifyZhMvcTest.TestBeans.class})
class SiteVerifyZhMvcTest {

    static final long T0 = 2_000_000_000L;
    static final String CODE = "们们们们";

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

        @Bean
        @Primary
        CaptchaSettings zhCaptcha() {
            return CaptchaSettings.fromLegacy(Map.of("codeSet", "1234567890", "length", 4L, "fontSize", 16L,
                    "useNoise", false, "useCurve", false, "useZh", true));
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

    @Test
    void imageHasLegacySizeAndDrawsTheGlyphs() throws Exception {
        MvcResult r = mvc.perform(get("/index.php/verify/index.html?r=0.5")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("image/png");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        // 旧版中文分支（golden captcha_image 的 zh-ttfs-font）同为 128 × 40
        JsonNode golden = MemberGolden.load(MemberGolden.PHP80).get("captcha_image");
        for (JsonNode c : golden) {
            if (c.get("label").asString().equals("zh-ttfs-font")) {
                assertThat(img.getWidth()).isEqualTo(c.get("width").asInt());
                assertThat(img.getHeight()).isEqualTo(c.get("height").asInt());
            }
        }
        int bg = img.getRGB(0, 0);
        int ink = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                ink += img.getRGB(x, y) != bg ? 1 : 0;
            }
        }
        assertThat(ink).as("四个点阵字都画出来了").isGreaterThan(4 * 40);
    }

    @Test
    void correctPassesOnceViaGetAndPostThenReplayFails() throws Exception {
        Cookie session = newCaptcha(null);
        assertThat(check(session, CODE, null)).isEqualTo("1");
        assertThat(check(session, CODE, null)).as("重放").isEqualTo("0");

        newCaptcha(session);
        MvcResult r = mvc.perform(post("/index.php/verify/check").cookie(session).characterEncoding("UTF-8")
                .contentType("application/x-www-form-urlencoded")
                .content("verify=" + URLEncoder.encode(CODE, StandardCharsets.UTF_8))).andReturn();
        assertThat(r.getResponse().getContentAsString()).isEqualTo("1");
    }

    @Test
    void wrongPartialOrTraditionalBurnsTheCode() throws Exception {
        for (String wrong : List.of("们们们他", "们们们", "們們們們", CODE + " ")) {
            Cookie session = newCaptcha(null);
            assertThat(check(session, wrong, null)).as(wrong).isEqualTo("0");
            assertThat(check(session, CODE, null)).as(wrong + " 之后已作废（D-B08-4）").isEqualTo("0");
        }
    }

    @Test
    void expiresAfter1800SecondsAndIdIsIgnored() throws Exception {
        Cookie session = newCaptcha(null);
        TestBeans.CLOCK.advanceSeconds(1800);
        try {
            assertThat(check(session, CODE, "zh")).as("1800 秒仍有效；id 被忽略，校验默认槽（黄金 zh-default-slot）")
                    .isEqualTo("1");
        } finally {
            TestBeans.CLOCK.advanceSeconds(-1800);
        }
        newCaptcha(session);
        TestBeans.CLOCK.advanceSeconds(1801);
        try {
            assertThat(check(session, CODE, null)).isEqualTo("0");
        } finally {
            TestBeans.CLOCK.advanceSeconds(-1801);
        }
    }

    private Cookie newCaptcha(Cookie session) throws Exception {
        MvcResult r = mvc.perform(session == null ? get("/index.php/verify/index")
                : get("/index.php/verify/index").cookie(session)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        if (session != null) {
            return session;
        }
        String h = r.getResponse().getHeader("Set-Cookie");
        return new Cookie("PHPSESSID", h.substring("PHPSESSID=".length(), h.indexOf(';')));
    }

    private String check(Cookie session, String code, String id) throws Exception {
        MvcResult r = mvc.perform(get(URI.create("/index.php/verify/check?verify="
                + URLEncoder.encode(code, StandardCharsets.UTF_8) + (id == null ? "" : "&id=" + id))).cookie(session))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getContentAsString();
    }
}

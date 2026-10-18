package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * 中文码表混有 ASCII、全角标点、繁体与扩展区汉字时经过控制器的生成与校验（B08 第二轮修正，17 文档第 2.4 节）：旧
 * {@code extra/captcha.php} 设 {@code useZh=true}、自定义 {@code zhSet}。随机源让每次 {@code nextInt(n)} 依次返回 0、1、2…
 * （每个请求前重置），所以验证码恒为码表的前 {@code length} 个字 {@code A，們㐀𠀀}。
 */
@WebMvcTest(controllers = SiteVerifyController.class,
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.legacy-config.dir=../application"})
@Import({SiteConfiguration.class, SiteVerifyZhMixedSetMvcTest.TestBeans.class})
class SiteVerifyZhMixedSetMvcTest {

    static final long T0 = 2_000_000_000L;
    static final String ZH_SET = "A，們㐀𠀀我ａ";
    static final String CODE = "A，們㐀𠀀";

    @TestConfiguration
    static class TestBeans {
        static final MutableClock CLOCK = new MutableClock(T0);
        static final AtomicInteger NEXT = new AtomicInteger();

        @Bean
        @Primary
        SessionStore testSessionStore() {
            return new SessionStore(CLOCK, Duration.ofMinutes(30), 1000);
        }

        @Bean
        @Primary
        RandomGenerator countingRandom() {
            return new RandomGenerator() {
                @Override
                public long nextLong() {
                    return 0L;
                }

                @Override
                public int nextInt(int bound) {
                    return NEXT.getAndIncrement() % bound;
                }
            };
        }

        @Bean
        @Primary
        CaptchaSettings mixedZhCaptcha() {
            return CaptchaSettings.fromLegacy(Map.of("codeSet", "1234567890", "length", 5L, "fontSize", 16L,
                    "useNoise", false, "useCurve", false, "useZh", true, "zhSet", ZH_SET));
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    CaptchaSettings settings;
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
    void resetRandom() {
        TestBeans.NEXT.set(0);
    }

    @Test
    void mixedSetIsKeptAndImageDrawsEveryCharacter() throws Exception {
        assertThat(settings.zhSet()).isEqualTo(ZH_SET);
        assertThat(settings.missingZhGlyphs()).containsExactly((int) '們', 0x3400, 0x20000);
        MvcResult r = mvc.perform(get("/index.php/verify/index")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("image/png");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        // 旧公式：5 × 16 × 1.5 + 5 × 16 / 2 = 160，高 16 × 2.5 = 40
        assertThat(img.getWidth()).isEqualTo(160);
        assertThat(img.getHeight()).isEqualTo(40);
        int bg = img.getRGB(0, 0);
        // 第 i 个字从 x = 16 × (i+1) × 1.5 起画（旋转后可能伸出几像素）；每一段都有墨迹，缺字形的字画了缺字框
        for (int i = 0; i < 5; i++) {
            int from = (int) (16 * (i + 1) * 1.5) + 2;
            int ink = 0;
            for (int x = from; x < Math.min(from + 16, img.getWidth()); x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    ink += img.getRGB(x, y) != bg ? 1 : 0;
                }
            }
            assertThat(ink).as("第 %d 个字", i).isGreaterThan(8);
        }
    }

    @Test
    void checksTheMixedCodeLikeTheLegacy() throws Exception {
        Cookie session = newCaptcha(null);
        assertThat(check(session, "a，們㐀𠀀")).as("ASCII 字母不分大小写").isEqualTo("1");
        assertThat(check(session, "a，們㐀𠀀")).as("重放").isEqualTo("0");

        for (String wrong : List.of("A,們㐀𠀀", "A，们㐀𠀀", "A，們㐀", "Ａ，們㐀𠀀")) {
            newCaptcha(session);
            assertThat(check(session, wrong)).as(wrong).isEqualTo("0");
            assertThat(check(session, CODE)).as(wrong + " 之后已作废（D-B08-4）").isEqualTo("0");
        }
    }

    private Cookie newCaptcha(Cookie session) throws Exception {
        TestBeans.NEXT.set(0);
        MvcResult r = mvc.perform(session == null ? get("/index.php/verify/index")
                : get("/index.php/verify/index").cookie(session)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        if (session != null) {
            return session;
        }
        String h = r.getResponse().getHeader("Set-Cookie");
        return new Cookie("PHPSESSID", h.substring("PHPSESSID=".length(), h.indexOf(';')));
    }

    private String check(Cookie session, String code) throws Exception {
        MvcResult r = mvc.perform(get(URI.create("/index.php/verify/check?verify="
                + URLEncoder.encode(code, StandardCharsets.UTF_8))).cookie(session)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getContentAsString();
    }
}

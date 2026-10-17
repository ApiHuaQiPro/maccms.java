package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.PlaybackFixtures.MEDIA;
import static com.maccms.backend.site.web.PlaybackFixtures.MEDIA2;
import static com.maccms.backend.site.web.PlaybackFixtures.allowed;
import static com.maccms.backend.site.web.PlaybackFixtures.media;
import static com.maccms.backend.site.web.PlaybackFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.PasswordTarget;
import com.maccms.backend.content.application.PlaybackEntry;
import com.maccms.backend.content.application.VideoPlaybackService;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.site.config.SiteConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 播放/下载四个入口（B05，15 文档第 6 节；D-B05-1～4）的 MockMvc 测试：content 服务是替身（合成数据），播放器配置取合成的旧配置目录
 * （src/test/resources/site/play/legacy-config，app.player_sort=1，含外部解析地址）。W1、W2 都打开（生产默认）；关闭态见
 * {@link SitePlaybackSwitchesMvcTest}。player_aaaa 与 PHP 黄金样本的逐字段对照见 PlaybackGoldenMySqlIT。
 */
@WebMvcTest(controllers = {SitePlaybackController.class, PlayerConfigController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.legacy-config.dir=" + PlaybackFixtures.LEGACY_DIR,
        "maccms.site.password-tickets.secret=" + PlaybackFixtures.SECRET,
        "maccms.browser-external.w1=true",
        "maccms.browser-external.w2=true"})
@Import(SiteConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SitePlaybackPagesMvcTest {

    static final String PLAY = "/index.php/vod/play/id/1/sid/1/nid/2.html";
    static final String PLAYER = "/index.php/vod/player/id/1/nid/2/sid/1.html";
    static final String DOWN = "/index.php/vod/down/id/1/sid/1/nid/1.html";
    static final String DOWNER = "/index.php/vod/downer/id/1/nid/1/sid/1.html";
    static final String STRICT = SiteHtmlRenderer.CONTENT_SECURITY_POLICY;
    private static final Pattern NONCE = Pattern.compile("script-src 'self' 'nonce-([A-Za-z0-9+/=]+)'");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ContentReadService content;
    @MockitoBean
    VideoPlaybackService playback;

    @BeforeEach
    void setUp() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
        when(playback.access(eq(1L), any(), any())).thenReturn(allowed());
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    private static Document html(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertNoStore(MvcResult r) {
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex");
    }

    // ------------------------------------------------------------------ 外层播放页

    @Test
    void playPageIsScriptFreeAndOnlyFramesTheSameOriginPlayer() throws Exception {
        MvcResult r = fetch(PLAY);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(r);
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).isEqualTo(STRICT).contains("script-src 'none'");
        Document doc = html(r);
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(doc.select("script")).isEmpty();
        assertThat(body).doesNotContain("example.invalid").doesNotContain("player_aaaa");
        Element frame = doc.selectFirst("div.player-frame > iframe.player-frame__iframe");
        assertThat(frame).isNotNull();
        assertThat(frame.attr("src")).isEqualTo(PLAYER);
        assertThat(frame.attr("title")).isNotBlank();
        assertThat(doc.select("iframe")).hasSize(1);
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test" + PLAY);
        assertThat(doc.selectFirst("h1").text()).isEqualTo("播放样本 <b>&");
        // 播放组按播放器 sort 降序（app.player_sort=1：dplayer 908 在 videojs 907 之前），组名为配置的 show
        assertThat(doc.select(".player-sources__name").eachText()).containsExactly("DPlayer-H5播放器", "videojs-H5播放器");
        assertThat(doc.select(".player-episodes__link[aria-current=page]").eachAttr("href")).containsExactly(PLAY);
        assertThat(doc.select(".player-episodes").first().select("a").eachAttr("href")).containsExactly(
                "/index.php/vod/play/id/1/sid/1/nid/1.html", PLAY, "/index.php/vod/play/id/1/sid/1/nid/3.html");
        assertThat(doc.selectFirst("a.player-page__prev").attr("href")).isEqualTo("/index.php/vod/play/id/1/sid/1/nid/1.html");
        assertThat(doc.selectFirst("a.player-page__next").attr("href")).isEqualTo("/index.php/vod/play/id/1/sid/1/nid/3.html");
        assertThat(doc.select(".breadcrumb__item").eachText()).containsExactly("首页", "电影", "动作片", "播放样本 <b>&", "第2集");
        assertThat(doc.select(".player-page__trial")).isEmpty();
    }

    @Test
    void unknownEpisodesQueriesAndUnapprovedFormsAre404() throws Exception {
        for (String path : List.of("/index.php/vod/play/id/1/sid/1/nid/9.html", "/index.php/vod/play/id/1/sid/3/nid/1.html",
                "/index.php/vod/play/id/1/sid/1.html", "/index.php/vod/play/id/1/sid/1/nid/01.html",
                "/index.php/vod/player/id/1/nid/9/sid/1.html", "/index.php/vod/down/id/1/sid/2/nid/9.html",
                "/index.php/vod/play/id/1/sid/1/nid/1/x/1.html")) {
            assertThat(fetch(path).getResponse().getStatus()).as(path).isEqualTo(404);
        }
        assertThat(mvc.perform(get(PLAY + "?nid=3")).andReturn().getResponse().getStatus()).isEqualTo(404);
        // 非规范参数顺序仍可访问，canonical 指向生成器的输出
        Document doc = html(fetch("/index.php/vod/play/nid/2/sid/1/id/1.html"));
        assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).isEqualTo("http://maccms.local.test" + PLAY);
    }

    @Test
    void deniedOutcomesAreFixed403NoticesWithoutAddresses() throws Exception {
        for (PlaybackOutcome o : List.of(PlaybackOutcome.NO_PERMISSION, PlaybackOutcome.POINTS_REQUIRED,
                PlaybackOutcome.COPYRIGHT_RESTRICTED)) {
            when(playback.access(eq(1L), any(), any())).thenReturn(result(o, media(), 0));
            for (String path : List.of(PLAY, PLAYER)) {
                MvcResult r = fetch(path);
                assertThat(r.getResponse().getStatus()).as(o + " " + path).isEqualTo(403);
                assertNoStore(r);
                assertThat(r.getResponse().getHeader("Content-Security-Policy")).isEqualTo(STRICT);
                String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertThat(body).doesNotContain("example.invalid", "player_aaaa", "data-player", "<script");
                assertThat(html(r).select(".access-notice")).hasSize(1);
            }
        }
        when(playback.access(eq(1L), any(), any())).thenReturn(result(PlaybackOutcome.NO_PERMISSION, media(), 0));
        String text = html(fetch(PLAY)).selectFirst(".access-notice__message").text();
        // 不一律声称登录就能解决（D-B05-2）
        assertThat(text).isEqualTo(PlaybackPages.NO_PLAY_PERMISSION).doesNotContain("登录");
    }

    // ------------------------------------------------------------------ 播放器内页

    private static String nonceOf(MvcResult r) {
        Matcher m = NONCE.matcher(r.getResponse().getHeader("Content-Security-Policy"));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void playerFrameHasPerResponseNonceSameOriginScriptsAndLegacyPlayerData() throws Exception {
        MvcResult r = fetch(PLAYER);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(r);
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        String nonce = nonceOf(r);
        assertThat(nonceOf(fetch(PLAYER))).as("nonce 逐响应生成").isNotEqualTo(nonce);
        assertThat(csp).startsWith("default-src 'none'; script-src 'self' 'nonce-" + nonce + "'; style-src 'self' 'unsafe-inline'")
                // W1：play.prestrain 是外部地址（合成配置 //ad.example.invalid/pre.html）；W2：本集的外部地址
                .contains("frame-src 'self' ad.example.invalid https://media.example.invalid",
                        "media-src 'self' blob: https://media.example.invalid")
                .doesNotContain("unsafe-eval", "http: ", "https: ", "*;");
        // 外部来源从不进入 script-src（W2 不打开任意脚本来源）
        assertThat(csp.substring(csp.indexOf("script-src"), csp.indexOf(';', csp.indexOf("script-src"))))
                .isEqualTo("script-src 'self' 'nonce-" + nonce + "'");
        Document doc = html(r);
        Element stage = doc.selectFirst("div#player-stage.player-stage");
        assertThat(stage).isNotNull();
        assertThat(stage.attr("data-trial-minutes")).isEqualTo("0");
        assertThat(doc.select("script")).allSatisfy(s -> {
            assertThat(s.attr("nonce")).isEqualTo(nonce);
            assertThat(s.attr("src")).isNotEmpty();
            assertThat(s.data()).isEmpty();
        });
        assertThat(stage.select("script").eachAttr("src")).containsExactly("/site-player/player-boot.js",
                "/static/js/jquery.js", "/static/js/playerconfig.js", "/static/js/player.js");
        assertThat(doc.select("#trial-message")).isEmpty();
        JsonNode data = JSON.readTree(stage.attr("data-player"));
        assertThat(data.get("maccms").get("path").asString()).isEmpty();
        JsonNode p = data.get("player");
        assertThat(p.properties()).extracting(java.util.Map.Entry::getKey).containsExactly("flag", "encrypt", "trysee",
                "points", "link", "link_next", "link_pre", "vod_data", "url", "url_next", "from", "server", "note", "id",
                "sid", "nid");
        assertThat(p.get("url").asString()).isEqualTo(MEDIA2);
        assertThat(p.get("url_next").asString()).isEqualTo("/upload/vod/v1-3.mp4");
        assertThat(p.get("link").asString()).isEqualTo("/index.php/vod/play/id/1/sid/1/nid/1.html");
        assertThat(p.get("link_pre").asString()).isEqualTo("/index.php/vod/play/id/1/sid/1/nid/1.html");
        assertThat(p.get("link_next").asString()).isEqualTo("/index.php/vod/play/id/1/sid/1/nid/3.html");
        assertThat(p.get("from").asString()).isEqualTo("dplayer");
        assertThat(p.get("server").asString()).isEqualTo("server1");
        assertThat(p.get("note").asString()).isEqualTo("备注一");
        assertThat(p.get("id").asString()).isEqualTo("1");
        assertThat(p.get("sid").asInt()).isEqualTo(1);
        assertThat(p.get("nid").asInt()).isEqualTo(2);
        assertThat(p.get("vod_data").get("vod_name").asString()).isEqualTo("播放样本 <b>&amp;");
        // 播放器内页的 HTML 中地址只以属性转义后的 JSON 出现，没有内联脚本
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<b>&amp;")
                .doesNotContain("var player_aaaa");
    }

    @Test
    void trialFrameCarriesMinutesAndTheSameOriginTrialScript() throws Exception {
        when(playback.access(eq(1L), any(), any())).thenReturn(result(PlaybackOutcome.TRIAL, media(), 7));
        MvcResult r = fetch(PLAYER);
        Document doc = html(r);
        String nonce = nonceOf(r);
        assertThat(doc.selectFirst("#player-stage").attr("data-trial-minutes")).isEqualTo("7");
        Element msg = doc.selectFirst("p#trial-message.trial-message[role=status]");
        assertThat(msg).isNotNull();
        Element trial = doc.selectFirst("script[src=/site/trial.js]");
        assertThat(trial).isNotNull();
        assertThat(trial.attr("nonce")).isEqualTo(nonce);
        assertThat(JSON.readTree(doc.selectFirst("#player-stage").attr("data-player")).get("player").get("trysee").asInt())
                .isEqualTo(7);
        assertThat(html(fetch(PLAY)).select(".player-page__trial")).hasSize(1);
    }

    @Test
    void protocolRelativeAndUploadAddressesFollowTheLegacyRules() throws Exception {
        JsonNode p = JSON.readTree(html(fetch("/index.php/vod/player/id/1/nid/1/sid/2.html")).selectFirst("#player-stage")
                .attr("data-player")).get("player");
        assertThat(p.get("url").asString()).isEqualTo("//cdn.example.invalid/v1/hd.mp4");
        assertThat(p.get("from").asString()).isEqualTo("videojs");
        JsonNode up = JSON.readTree(html(fetch("/index.php/vod/player/id/1/nid/3/sid/1.html")).selectFirst("#player-stage")
                .attr("data-player")).get("player");
        assertThat(up.get("url").asString()).isEqualTo("/upload/vod/v1-3.mp4");
        assertThat(up.get("link_next").asString()).isEmpty();
    }

    @Test
    void unsafeAddressesAndPlayerCodesAreNeverHandedToTheLegacyScript() throws Exception {
        for (String[] c : List.of(new String[] {"dplayer", "第1集$javascript:alert(1)"},
                new String[] {"dplayer", "第1集$https://a.example.invalid/x\"><script>alert(1)</script>"},
                new String[] {"dplayer", "第1集$https://a.example.invalid/x$x\"onload=\"y"},
                new String[] {"dplayer", "第1集$https://a.example.invalid/1.mp4$x\"><b"})) {
            when(playback.access(eq(1L), any(), any())).thenReturn(result(PlaybackOutcome.ALLOWED, media(c[0], c[1]), 0));
            MvcResult r = fetch("/index.php/vod/player/id/1/nid/1/sid/1.html");
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(r.getResponse().getStatus()).as(c[1]).isEqualTo(200);
            assertThat(html(r).selectFirst(".access-notice__message").text()).isEqualTo(PlaybackPages.UNUSABLE);
            assertThat(body).doesNotContain("alert(1)", "<script", "onload", "\"><b");
        }
    }

    @Test
    void ivaSourceAddsOnlyItsScriptHostWhenW1IsOpen() throws Exception {
        when(playback.access(eq(1L), any(), any()))
                .thenReturn(result(PlaybackOutcome.ALLOWED, media("iva", "第1集$https://media.example.invalid/1.mp4"), 0));
        String csp = fetch("/index.php/vod/player/id/1/nid/1/sid/1.html").getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self' 'nonce-").contains(" *.videojj.com;");
        // 解析来源：只放行该来源实际使用的解析地址（W1）
        when(playback.access(eq(1L), any(), any()))
                .thenReturn(result(PlaybackOutcome.ALLOWED, media("jxown", "第1集$https://media.example.invalid/1.mp4"), 0));
        csp = fetch("/index.php/vod/player/id/1/nid/1/sid/1.html").getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("frame-src 'self' http://jx2.example.invalid ad.example.invalid https://media.example.invalid")
                .doesNotContain("jx.example.invalid");
        assertThat(csp.substring(csp.indexOf("script-src"), csp.indexOf(';', csp.indexOf("script-src"))))
                .doesNotContain("example.invalid");
    }

    // ------------------------------------------------------------------ 密码（D-B05-3）

    @Test
    void playerPasswordFlowUsesSignedHttpOnlyTicket(CapturedOutput output) throws Exception {
        String pwd = "Ab9-SYNTHETIC-PWD";
        when(playback.access(eq(1L), eq(PlaybackEntry.PLAYER), isNull()))
                .thenReturn(result(PlaybackOutcome.PASSWORD_REQUIRED, media(), 0));
        when(playback.access(eq(1L), eq(PlaybackEntry.PLAYER), any(PasswordProof.class))).thenReturn(allowed());
        when(playback.verifyPassword(1L, PasswordTarget.VIDEO_PLAY, pwd)).thenReturn(true);

        MvcResult form = fetch(PLAYER);
        assertThat(form.getResponse().getStatus()).isEqualTo(403);
        assertNoStore(form);
        Element f = html(form).selectFirst("form.password-page__form");
        assertThat(f.attr("method")).isEqualTo("post");
        assertThat(f.attr("action")).isEqualTo(PLAYER);
        assertThat(f.selectFirst("input[name=pwd][type=password]")).isNotNull();
        assertThat(html(form).select("script")).isEmpty();

        MvcResult wrong = mvc.perform(post(PLAYER).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("pwd", "nope"))
                .andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(403);
        assertThat(wrong.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(html(wrong).selectFirst(".password-page__error").text()).contains("密码不正确");

        MvcResult ok = mvc.perform(post(PLAYER).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("pwd", pwd))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(303);
        assertThat(ok.getResponse().getHeader("Location")).isEqualTo(PLAYER);
        assertThat(ok.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String setCookie = ok.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("maccms_pwd_v4_1=v1.").contains("; Path=/", "; Max-Age=7200", "; HttpOnly",
                "; SameSite=Lax").doesNotContain("Secure").doesNotContain(pwd);
        String ticket = setCookie.substring("maccms_pwd_v4_1=".length(), setCookie.indexOf(';'));

        MvcResult player = mvc.perform(get(PLAYER).cookie(new Cookie("maccms_pwd_v4_1", ticket))).andReturn();
        assertThat(player.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<PasswordProof> proof = ArgumentCaptor.forClass(PasswordProof.class);
        verify(playback, org.mockito.Mockito.atLeastOnce()).access(eq(1L), eq(PlaybackEntry.PLAYER), proof.capture());
        assertThat(proof.getAllValues()).anySatisfy(pp -> assertThat(pp).isNotNull());

        // 伪造、跨用途、跨内容的票据都不被接受（交给 content 的凭据为空）
        for (Cookie c : List.of(new Cookie("maccms_pwd_v4_1", ticket.substring(0, ticket.length() - 2) + "AA"),
                new Cookie("maccms_pwd_v5_1", ticket), new Cookie("maccms_pwd_v4_2", ticket))) {
            MvcResult denied = mvc.perform(get(PLAYER).cookie(c)).andReturn();
            assertThat(denied.getResponse().getStatus()).as(c.getName()).isEqualTo(403);
        }
        assertThat(output.getAll()).doesNotContain(pwd).doesNotContain(ticket);
    }

    @Test
    void passwordAttemptsAreRateLimitedWithoutQueryingTheDatabase() throws Exception {
        when(playback.access(eq(1L), eq(PlaybackEntry.DOWNER), isNull()))
                .thenReturn(result(PlaybackOutcome.PASSWORD_REQUIRED, media(), 0));
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            int status = mvc.perform(post(DOWNER).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("pwd", "x" + i))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                limited++;
            } else {
                assertThat(status).isEqualTo(403);
            }
        }
        assertThat(limited).isEqualTo(2);
        verify(playback, org.mockito.Mockito.times(10)).verifyPassword(eq(1L), eq(PasswordTarget.VIDEO_DOWN), any());
    }

    @Test
    void passwordPagesOnlyAcceptPost() throws Exception {
        assertThat(mvc.perform(post(PLAY).param("pwd", "x")).andReturn().getResponse().getStatus()).isIn(404, 405);
        verify(playback, never()).verifyPassword(anyLong(), any(), any());
    }

    // ------------------------------------------------------------------ 下载

    @Test
    void downPageListsEpisodesAndDownerShowsTheAddressOnlyAfterChecks() throws Exception {
        when(playback.access(eq(1L), eq(PlaybackEntry.DOWN_PAGE), any())).thenReturn(result(PlaybackOutcome.ALLOWED,
                new com.maccms.backend.content.application.view.VideoMediaDto("http$$$xunlei",
                        "下载1$https://dl.example.invalid/v1/1.zip#下载2$upload/down/v1-2.zip$$$迅雷$thunder://QUFo", "", "", 0), 0));
        when(playback.access(eq(1L), eq(PlaybackEntry.DOWNER), any())).thenReturn(result(PlaybackOutcome.ALLOWED,
                new com.maccms.backend.content.application.view.VideoMediaDto("http$$$xunlei",
                        "下载1$https://dl.example.invalid/v1/1.zip#下载2$upload/down/v1-2.zip$$$迅雷$thunder://QUFo", "", "", 0), 0));
        MvcResult down = fetch(DOWN);
        assertThat(down.getResponse().getStatus()).isEqualTo(200);
        assertThat(down.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("example.invalid", "thunder:");
        assertThat(html(down).select(".download-list__link").eachAttr("href")).containsExactly(DOWNER,
                "/index.php/vod/downer/id/1/nid/2/sid/1.html", "/index.php/vod/downer/id/1/nid/1/sid/2.html");
        Document downer = html(fetch(DOWNER));
        Element link = downer.selectFirst("a.download-page__link");
        assertThat(link.attr("href")).isEqualTo("https://dl.example.invalid/v1/1.zip");
        assertThat(link.attr("rel")).contains("nofollow", "noopener", "noreferrer");
        assertThat(html(fetch("/index.php/vod/downer/id/1/nid/2/sid/1.html")).selectFirst("a.download-page__link")
                .attr("href")).isEqualTo("/upload/down/v1-2.zip");
        Document thunder = html(fetch("/index.php/vod/downer/id/1/nid/1/sid/2.html"));
        assertThat(thunder.select("a.download-page__link")).isEmpty();
        assertThat(thunder.selectFirst("code.download-page__text").text()).isEqualTo("thunder://QUFo");
    }

    // ------------------------------------------------------------------ playerconfig.js

    @Test
    void playerConfigScriptIsGeneratedFromLegacyConfig() throws Exception {
        MvcResult r = fetch("/static/js/playerconfig.js");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/javascript");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        String js = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(js).startsWith("var MacPlayerConfig={};\r\n//参数开始\r\nMacPlayerConfig={")
                .contains("jx.example.invalid").endsWith("//缓存结束");
        assertThat(mvc.perform(get("/static/js/playerconfig.js?t=20260101")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).isEqualTo(js);
    }

    @Test
    void playerFromNotInTheLegacyConfigIsNotAPlaySource() throws Exception {
        when(playback.access(eq(1L), any(), any()))
                .thenReturn(result(PlaybackOutcome.ALLOWED, media("nosuchplayer", "第1集$" + MEDIA), 0));
        // mac_play_list 只保留配置中启用的播放器（common.php:1380-1394）：该组不存在 → 404
        assertThat(fetch("/index.php/vod/player/id/1/nid/1/sid/1.html").getResponse().getStatus()).isEqualTo(404);
        when(playback.access(eq(1L), any(), any()))
                .thenReturn(result(PlaybackOutcome.ALLOWED, media("offplayer", "第1集$" + MEDIA), 0));
        assertThat(fetch("/index.php/vod/play/id/1/sid/1/nid/1.html").getResponse().getStatus()).isEqualTo(404);
    }
}

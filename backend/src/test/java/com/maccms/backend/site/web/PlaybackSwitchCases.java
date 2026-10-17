package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.PlaybackFixtures.media;
import static com.maccms.backend.site.web.PlaybackFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoPlaybackService;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.content.application.view.VideoMediaDto;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * W1/W2 四种组合（队长对 15 文档第 6.3 节的修正：00/01/10/11 分别测试）的共用用例，由四个子类以不同部署参数运行。
 * W1 关只去掉对应的解析/iva/prestrain/buffer 外链，W2 关只去掉对应的媒体/下载/外链图片；两者全关时响应内容、playerconfig.js
 * 与 CSP 中完全没有外部来源。合成配置：play.prestrain 为外部地址、play.buffer 为站内地址；Logo 为外部地址。
 */
abstract class PlaybackSwitchCases {

    static final String LOGO = "https://logo.example.invalid/logo.png";
    static final String LOCAL_MEDIA = "第1集$/upload/vod/1.mp4#第2集$https://media.example.invalid/2.mp4";
    private static final Pattern EXTERNAL = Pattern.compile("(?i)(?:https?:)?\\\\?/\\\\?/(?!maccms\\.local\\.test)[a-z0-9-]+\\.[a-z0-9.-]+"
            + "|\\*\\.[a-z0-9.-]+|[a-z0-9-]+\\.example\\.invalid");

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ContentReadService content;
    @MockitoBean
    VideoPlaybackService playback;

    abstract boolean w1();

    abstract boolean w2();

    @BeforeEach
    void stub() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
    }

    private void serve(String from, String urls) {
        when(playback.access(eq(1L), any(), any())).thenReturn(result(PlaybackOutcome.ALLOWED,
                new VideoMediaDto(from, urls, "", "", 0), 0));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> externals(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = EXTERNAL.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static String notice(MvcResult r) throws Exception {
        var n = Jsoup.parse(body(r)).selectFirst(".access-notice__message");
        return n == null ? null : n.text();
    }

    private static String dataPlayer(MvcResult r) throws Exception {
        return Jsoup.parse(body(r)).selectFirst("#player-stage").attr("data-player");
    }

    @Test
    void externalMediaFollowsW2Only() throws Exception {
        serve("dplayer", "第1集$https://media.example.invalid/1.mp4");
        MvcResult r = fetch("/index.php/vod/player/id/1/nid/1/sid/1.html");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        if (w2()) {
            assertThat(notice(r)).isNull();
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("media-src 'self' blob: https://media.example.invalid");
        } else {
            assertThat(notice(r)).isEqualTo(PlaybackPages.W2_PLAY_CLOSED);
            assertThat(body(r)).doesNotContain("media.example.invalid");
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).isEqualTo(SiteHtmlRenderer.CONTENT_SECURITY_POLICY);
        }
    }

    @Test
    void localMediaAlwaysPlaysAndNextExternalAddressFollowsW2() throws Exception {
        serve("dplayer", LOCAL_MEDIA);
        MvcResult r = fetch("/index.php/vod/player/id/1/nid/1/sid/1.html");
        assertThat(notice(r)).isNull();
        var p = JsonMapper.builder().build().readTree(dataPlayer(r)).get("player");
        assertThat(p.get("url").asString()).isEqualTo("/upload/vod/1.mp4");
        assertThat(p.get("url_next").asString()).isEqualTo(w2() ? "https://media.example.invalid/2.mp4" : "");
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        // 本集是站内地址：媒体来源只有 'self'；W1 决定外部缓冲/预加载页
        assertThat(csp).contains("media-src 'self' blob:;");
        assertThat(csp.contains("ad.example.invalid")).isEqualTo(w1());
        assertThat(csp).doesNotContain("media.example.invalid");
    }

    @Test
    void externalParseSourcesFollowW1Only() throws Exception {
        serve("jxglobal", "第1集$/upload/vod/1.mp4");
        MvcResult r = fetch("/index.php/vod/player/id/1/nid/1/sid/1.html");
        if (w1()) {
            assertThat(notice(r)).isNull();
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("frame-src 'self' https://jx.example.invalid");
        } else {
            assertThat(notice(r)).isEqualTo(PlaybackPages.W1_CLOSED);
            assertThat(body(r)).doesNotContain("example.invalid");
        }
        // 站内解析地址与 W1 无关
        serve("jxlocal", "第1集$/upload/vod/1.mp4");
        assertThat(notice(fetch("/index.php/vod/player/id/1/nid/1/sid/1.html"))).isNull();
    }

    @Test
    void ivaExternalScriptFollowsW1Only() throws Exception {
        serve("iva", "第1集$/upload/vod/1.mp4");
        MvcResult r = fetch("/index.php/vod/player/id/1/nid/1/sid/1.html");
        if (w1()) {
            assertThat(notice(r)).isNull();
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("*.videojj.com");
        } else {
            assertThat(notice(r)).isEqualTo(PlaybackPages.W1_CLOSED);
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).doesNotContain("videojj");
        }
    }

    @Test
    void downloadAddressesFollowW2Only() throws Exception {
        for (String url : List.of("https://dl.example.invalid/1.zip", "thunder://QUFo", "magnet:?xt=urn:btih:abc")) {
            when(playback.access(eq(1L), any(), any())).thenReturn(result(PlaybackOutcome.ALLOWED,
                    new VideoMediaDto("http", "下载$" + url, "", "", 0), 0));
            MvcResult r = fetch("/index.php/vod/downer/id/1/nid/1/sid/1.html");
            if (w2()) {
                assertThat(body(r)).as(url).contains(url.startsWith("https") ? url : url.substring(0, 6));
            } else {
                assertThat(notice(r)).as(url).isEqualTo(PlaybackPages.W2_DOWN_CLOSED);
                assertThat(body(r)).as(url).doesNotContain(url);
            }
        }
        when(playback.access(eq(1L), any(), any())).thenReturn(result(PlaybackOutcome.ALLOWED,
                new VideoMediaDto("http", "下载$upload/down/1.zip", "", "", 0), 0));
        assertThat(Jsoup.parse(body(fetch("/index.php/vod/downer/id/1/nid/1/sid/1.html")))
                .selectFirst("a.download-page__link").attr("href")).isEqualTo("/upload/down/1.zip");
    }

    @Test
    void playerConfigScriptFollowsW1() throws Exception {
        String js = body(fetch("/static/js/playerconfig.js"));
        assertThat(js.contains("jx.example.invalid")).isEqualTo(w1());
        assertThat(js.contains("ad.example.invalid")).isEqualTo(w1());
        assertThat(js).contains("\\/static\\/player\\/loading.html", "\\/jx\\/?url=");
        if (!w1()) {
            assertThat(externals(js)).isEmpty();
        }
    }

    @Test
    void logoFollowsW2() throws Exception {
        serve("dplayer", LOCAL_MEDIA);
        Document doc = Jsoup.parse(body(fetch("/index.php/vod/play/id/1/sid/1/nid/1.html")));
        assertThat(doc.select("img.site-brand__logo").isEmpty()).isEqualTo(!w2());
        if (w2()) {
            assertThat(doc.selectFirst("img.site-brand__logo").attr("src")).isEqualTo(LOGO);
        }
    }

    /** 两者全关（00）：页面、playerconfig.js 与 CSP 中都没有任何外部来源。 */
    @Test
    void bothClosedLeavesNoExternalSourceAnywhere() throws Exception {
        if (w1() || w2()) {
            return;
        }
        List<String> found = new ArrayList<>();
        for (String[] c : List.of(new String[] {"dplayer", LOCAL_MEDIA}, new String[] {"iva", LOCAL_MEDIA},
                new String[] {"jxglobal", LOCAL_MEDIA}, new String[] {"dplayer", "第1集$https://media.example.invalid/1.mp4"})) {
            serve(c[0], c[1]);
            for (String path : List.of("/index.php/vod/play/id/1/sid/1/nid/1.html", "/index.php/vod/player/id/1/nid/1/sid/1.html",
                    "/index.php/vod/down/id/1/sid/1/nid/1.html", "/index.php/vod/downer/id/1/nid/1/sid/1.html")) {
                MvcResult r = fetch(path);
                found.addAll(externals(body(r)).stream().map(e -> path + " " + e).toList());
                found.addAll(externals(r.getResponse().getHeader("Content-Security-Policy")).stream()
                        .map(e -> path + " CSP " + e).toList());
            }
        }
        found.addAll(externals(body(fetch("/static/js/playerconfig.js"))));
        assertThat(found).isEmpty();
    }
}

package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.PlaybackFixtures.media;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentPurchases;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.PlaybackEntry;
import com.maccms.backend.content.application.VideoPlaybackService;
import com.maccms.backend.content.application.view.CategoryRefDto;
import com.maccms.backend.content.application.view.VideoMediaDto;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.content.application.view.VideoPlaybackResult;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.MemberGolden;
import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * B10 内容页接线（19 文档第 5 节）：需要积分时的购买确认（旧 vod/confirm：3003/4003，下载组 1 为 4001）、试看页的购买入口与试看结束提示
 * （旧 5001/5002，文字与 PHP 黄金相同）、已登录会员打开播放/下载页时的记录、详情页的"收藏"表单；游客的页面与 B05 相同（没有会员表单）。
 * 内容判定与会员服务都是替身；判定本身由 PurchasePopedomGoldenTest 对照 PHP 黄金。
 */
@WebMvcTest(controllers = {SitePlaybackController.class, PlayerConfigController.class, SiteContentController.class},
        properties = {
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/",
                "maccms.legacy-config.dir=" + PlaybackFixtures.LEGACY_DIR,
                "maccms.site.password-tickets.secret=" + PlaybackFixtures.SECRET,
                "maccms.browser-external.w1=true",
                "maccms.browser-external.w2=true"})
@Import(SiteConfiguration.class)
class SiteFinanceContentMvcTest {

    private static final String PLAY = "/index.php/vod/play/id/1/sid/1/nid/2.html";
    private static final String PLAYER = "/index.php/vod/player/id/1/nid/2/sid/1.html";
    private static final String DOWN = "/index.php/vod/down/id/1/sid/1/nid/1.html";
    private static final MemberGroup DEFAULT = new MemberGroup(2, "默认会员", 1, ",1,6,", "{}");
    private static final MemberState MEMBER = new MemberState(true, 7, "buyer007", 2, 2, DEFAULT, 0, false);

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ContentReadService content;
    @MockitoBean
    VideoPlaybackService playback;
    @MockitoBean
    MemberSessionService sessions;
    @MockitoBean
    MemberRecordService records;
    @MockitoBean
    MemberWalletService wallet;

    @BeforeEach
    void setUp() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
        when(records.available()).thenReturn(true);
        when(wallet.available()).thenReturn(true);
        when(records.purchases(anyLong())).thenReturn(ContentPurchases.NONE);
        member(10);
    }

    private void member(long points) {
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MEMBER, List.of(), true));
        when(wallet.wallet(anyLong())).thenReturn(Optional.of(new FinanceRecords.Wallet(7, "buyer007", 2, points, 0, 0)));
    }

    private void guest() {
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
    }

    private void outcome(PlaybackOutcome o, int trySee, int points) {
        boolean withMedia = o == PlaybackOutcome.ALLOWED || o == PlaybackOutcome.TRIAL;
        when(playback.access(eq(1L), anyInt(), anyInt(), any(), any())).thenReturn(new VideoPlaybackResult(o, 1,
                "样本", "", "", "", new CategoryRefDto(6, "动作片", "dongzuo", 1), 1_700_000_001L, trySee, points,
                withMedia ? Optional.of(media()) : Optional.empty()));
    }

    private Document html(String path) throws Exception {
        return Jsoup.parse(fetch(path).getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    private static Map<String, String> hidden(Element form) {
        Map<String, String> m = new LinkedHashMap<>();
        form.select("input[type=hidden]").forEach(e -> m.put(e.attr("name"), e.val()));
        return m;
    }

    /** 黄金 popedom 段里某个用例的提示原文。 */
    private static String goldenMsg(String label) {
        for (JsonNode c : MemberGolden.load("member/finance-php80-golden.json").get("popedom").get("cases")) {
            if (c.get("label").asString().equals(label)) {
                return c.get("msg").asString();
            }
        }
        throw new IllegalStateException(label);
    }

    @Test
    void pointsRequiredPlayIsAConfirmPageWithAPlainBuyForm() throws Exception {
        outcome(PlaybackOutcome.POINTS_REQUIRED, 0, 5);
        MvcResult r = fetch(PLAY);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).isEqualTo(SiteHtmlRenderer.CONTENT_SECURITY_POLICY);
        Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.selectFirst(".access-notice__message").text()).isEqualTo(goldenMsg("play-whole-not-purchased"));
        Element form = d.selectFirst("form.content-action--buy");
        assertThat(form.attr("method")).isEqualTo("post");
        assertThat(form.attr("action")).isEqualTo("/index.php/user/ajax_buy_popedom.html");
        assertThat(hidden(form)).containsExactly(Map.entry("mid", "1"), Map.entry("id", "1"), Map.entry("sid", "1"),
                Map.entry("nid", "2"), Map.entry("type", "4"));
        assertThat(d.select("script")).isEmpty();
        assertThat(d.select(".buy-confirm__login")).isEmpty();
        verify(records, never()).recordView(any(), anyInt(), anyLong(), anyInt(), anyInt());

        guest();
        Document g = html(PLAY);
        assertThat(g.select("form.content-action")).as("游客没有购买表单").isEmpty();
        assertThat(g.selectFirst(".buy-confirm__login a").attr("href")).isEqualTo("/index.php/user/login.html");
    }

    @Test
    void pointsRequiredDownloadDependsOnTheGroup() throws Exception {
        outcome(PlaybackOutcome.POINTS_REQUIRED, 0, 5);
        Document m = html(DOWN);
        assertThat(m.selectFirst(".access-notice__message").text()).isEqualTo(goldenMsg("down-not-purchased"));
        assertThat(hidden(m.selectFirst("form.content-action--buy"))).containsEntry("type", "5");
        guest();
        Document g = html(DOWN);
        assertThat(g.selectFirst(".access-notice__message").text()).as("组 1：4001")
                .isEqualTo(goldenMsg("down-guest-group"));
        assertThat(g.select("form.content-action")).isEmpty();
    }

    @Test
    void trialOffersTheLegacy5001Or5002AndTheFrameEndsWithTheSameText() throws Exception {
        outcome(PlaybackOutcome.TRIAL, 10, 5);
        member(8);
        Document can = html(PLAY);
        assertThat(can.selectFirst(".trial-offer__message").text()).isEqualTo(goldenMsg("player-trial-can-buy"));
        assertThat(hidden(can.selectFirst(".trial-offer form.content-action--buy"))).containsEntry("type", "4");
        Document frame = html(PLAYER);
        assertThat(frame.selectFirst("#player-stage").attr("data-trial-end-message"))
                .isEqualTo(goldenMsg("player-trial-can-buy"));
        member(3);
        Document poor = html(PLAY);
        assertThat(poor.selectFirst(".trial-offer__message").text()).isEqualTo(goldenMsg("player-trial-not-enough"));
        assertThat(poor.select(".trial-offer form")).isEmpty();
        assertThat(poor.selectFirst(".trial-offer__recharge a").attr("href")).isEqualTo("/index.php/user/buy.html");
        guest();
        assertThat(html(PLAY).select(".trial-offer")).as("游客与 B05 相同").isEmpty();
        assertThat(html(PLAYER).selectFirst("#player-stage").hasAttr("data-trial-end-message")).isFalse();
    }

    @Test
    void membersOpeningPlayOrDownPagesAreRecorded() throws Exception {
        outcome(PlaybackOutcome.ALLOWED, 0, 0);
        assertThat(fetch(PLAY).getResponse().getStatus()).isEqualTo(200);
        verify(records).recordView(eq(MEMBER), eq(4), eq(1L), eq(1), eq(2));
        when(playback.access(eq(1L), anyInt(), anyInt(), eq(PlaybackEntry.DOWN_PAGE), any())).thenReturn(
                PlaybackFixtures.result(PlaybackOutcome.ALLOWED, new VideoMediaDto("http",
                        "下载1$https://dl.example.invalid/v1/1.zip", "", "", 0), 0));
        assertThat(fetch(DOWN).getResponse().getStatus()).isEqualTo(200);
        verify(records).recordView(eq(MEMBER), eq(5), eq(1L), eq(1), eq(1));
        guest();
        fetch(PLAY);
        verify(records, org.mockito.Mockito.times(1)).recordView(any(), eq(4), anyLong(), anyInt(), anyInt());
    }

    @Test
    void videoDetailHasAFavoriteFormOnlyForMembers() throws Exception {
        when(content.getVideo(9)).thenReturn(SiteContentFixtures.normalDetail());
        when(content.listVideos(any())).thenReturn(SiteContentFixtures.page(List.of(), 1, 6, 0));
        Document d = html("/index.php/vod/detail/id/9.html");
        Element form = d.selectFirst(".content-actions form.content-action--favorite");
        assertThat(form.attr("action")).isEqualTo("/index.php/user/ajax_ulog.html");
        assertThat(hidden(form)).containsExactly(Map.entry("ac", "set"), Map.entry("mid", "1"), Map.entry("id", "9"),
                Map.entry("type", "2"));
        assertThat(d.select("script")).isEmpty();
        guest();
        assertThat(html("/index.php/vod/detail/id/9.html").select(".content-actions")).as("游客页面不变").isEmpty();
    }
}

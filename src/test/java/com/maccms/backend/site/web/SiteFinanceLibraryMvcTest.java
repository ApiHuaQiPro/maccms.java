package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentPurchases;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
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

/**
 * B10 文章、专题、演员详情的会员接线（19 文档第 5.2、5.4 节）：收费文章的购买确认表单（mid=2、type=1、sid=页码），三类详情的"收藏"表单
 * （旧 ulog_mid 2/3/8）；角色、网址等其他详情与游客页面没有会员表单。
 */
@WebMvcTest(controllers = {SiteLibraryController.class, SiteContentController.class}, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/",
        "maccms.site.password-tickets.secret=" + PlaybackFixtures.SECRET})
@Import(SiteConfiguration.class)
class SiteFinanceLibraryMvcTest {

    private static final MemberGroup DEFAULT = new MemberGroup(2, "默认会员", 1, ",1,6,", "{}");
    private static final MemberState MEMBER = new MemberState(true, 7, "buyer007", 2, 2, DEFAULT, 0, false);

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
    MemberSessionService sessions;
    @MockitoBean
    MemberRecordService records;
    @MockitoBean
    MemberWalletService wallet;

    @BeforeEach
    void setUp() {
        when(content.listCategories()).thenReturn(SiteContentFixtures.categories());
        when(articles.listCategories()).thenReturn(LibraryPageFixtures.artTypes());
        when(actors.listCategories()).thenReturn(LibraryPageFixtures.actorTypes());
        when(websites.listCategories()).thenReturn(LibraryPageFixtures.websiteTypes());
        when(articles.getArticle(eq(55L), anyInt(), any())).thenReturn(LibraryPageFixtures.article(55, 3));
        when(articles.getArticle(eq(60L), anyInt(), any())).thenThrow(new ApiException(ErrorCode.ARTICLE_POINTS_REQUIRED, "x"));
        when(articles.requiredPoints(60L)).thenReturn(Optional.of(6));
        when(topics.getTopic(7)).thenReturn(LibraryPageFixtures.topic(7));
        when(actors.getActor(8)).thenReturn(LibraryPageFixtures.actor(8));
        when(roles.getRole(9)).thenReturn(LibraryPageFixtures.role(9));
        when(records.available()).thenReturn(true);
        when(wallet.available()).thenReturn(true);
        when(records.purchases(anyLong())).thenReturn(ContentPurchases.NONE);
        when(wallet.wallet(anyLong())).thenReturn(Optional.of(new FinanceRecords.Wallet(7, "buyer007", 2, 10, 0, 0)));
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MEMBER, List.of(), true));
    }

    private void guest() {
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
    }

    private Document html(String path) throws Exception {
        return Jsoup.parse(mvc.perform(get(path)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Map<String, String> hidden(Element form) {
        Map<String, String> m = new LinkedHashMap<>();
        form.select("input[type=hidden]").forEach(e -> m.put(e.attr("name"), e.val()));
        return m;
    }

    @Test
    void chargedArticleOffersAPlainBuyFormForTheRequestedPage() throws Exception {
        Document d = html("/index.php/art/detail/id/60/page/3.html");
        assertThat(d.selectFirst(".access-notice__message").text()).isEqualTo("观看此数据，需要支付【6】积分，确认支付吗？");
        Element form = d.selectFirst("form.content-action--buy");
        assertThat(form.attr("method")).isEqualTo("post");
        assertThat(form.attr("action")).isEqualTo("/index.php/user/ajax_buy_popedom.html");
        assertThat(hidden(form)).containsExactly(Map.entry("mid", "2"), Map.entry("id", "60"), Map.entry("sid", "3"),
                Map.entry("nid", "0"), Map.entry("type", "1"));
        assertThat(d.select(".buy-confirm__login")).isEmpty();
        assertThat(d.select("script")).isEmpty();
    }

    @Test
    void articleTopicAndActorDetailsHaveFavoriteFormsForMembersOnly() throws Exception {
        Map<String, String[]> pages = Map.of("/index.php/art/detail/id/55.html", new String[] {"2", "55"},
                "/index.php/topic/detail/id/7.html", new String[] {"3", "7"},
                "/index.php/actor/detail/id/8.html", new String[] {"8", "8"});
        for (Map.Entry<String, String[]> p : pages.entrySet()) {
            Element form = html(p.getKey()).selectFirst(".content-actions form.content-action--favorite");
            assertThat(form).as(p.getKey()).isNotNull();
            assertThat(form.attr("action")).isEqualTo("/index.php/user/ajax_ulog.html");
            assertThat(hidden(form)).as(p.getKey()).containsExactly(Map.entry("ac", "set"),
                    Map.entry("mid", p.getValue()[0]), Map.entry("id", p.getValue()[1]), Map.entry("type", "2"));
        }
        assertThat(html("/index.php/role/detail/id/9.html").select(".content-actions")).as("角色没有收藏").isEmpty();
        guest();
        for (String path : pages.keySet()) {
            assertThat(html(path).select(".content-actions")).as("游客页面不变 " + path).isEmpty();
        }
    }
}

package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.content.application.view.VideoPlaybackResult;
import com.maccms.backend.content.infrastructure.ArtMapper;
import com.maccms.backend.content.infrastructure.ArtPointsRow;
import com.maccms.backend.content.infrastructure.ContentLookupMapper;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.GroupPermissionRow;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.TypeRow;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodPlaybackRow;
import com.maccms.backend.member.MemberGolden;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;

/**
 * D-B09-11（B10，docs/architecture/19-b10-member-finance.md 第 5.1 节）：会员组 &lt; 3 访问收费数据时按 {@code mac_ulog} 判断是否已购买。
 * 逐条复放 PHP 8.0 与 7.3 财务黄金的 {@code popedom} 段——旧 {@code check_user_popedom}（{@code index/controller/Base.php:92-227}）在会员
 * 99 有购买记录时的判定：整片/按集播放、试看内页（5001/5002/已购买）、下载（组 2 购买、组 1 先登录）、整篇/按页文章，以及价格改动后旧记录不再匹配。
 * 已购买查询用黄金写入的同一组 {@code mac_ulog} 行，按七项精确匹配（与 member 的实现相同）。
 *
 * <p>返回码对照：1 → 放行；3002 → 试看；3003/4001/4003 → 需要积分；5001/5002 → 试看（试看结束时的购买入口与提示在 site，见
 * SiteFinanceMvcTest）。唯一的有意差异：试看位而无播放位、已购买时，旧外层播放页仍是 3002（试看），完整播放由内页给出；新版外层页直接按放行
 * （同一结果，不再显示"试看"提示）。
 */
class PurchasePopedomGoldenTest {

    private static final long VOD = 9;

    @Test
    void purchasedAndUnpurchasedStatesMatchLegacyCheckUserPopedom() {
        for (String version : List.of("member/finance-php80-golden.json", "member/finance-php73-golden.json")) {
            JsonNode popedom = MemberGolden.load(version).get("popedom");
            List<PurchaseKey> owned = new ArrayList<>();
            for (JsonNode r : popedom.get("ulog")) {
                assertThat(r.get("user_id").asLong()).isEqualTo(99);
                owned.add(new PurchaseKey(r.get("ulog_mid").asInt(), r.get("ulog_type").asInt(), r.get("ulog_rid").asLong(),
                        r.get("ulog_sid").asInt(), r.get("ulog_nid").asInt(), r.get("ulog_points").asInt()));
            }
            ContentPurchases purchases = owned::contains;
            int compared = 0;
            for (JsonNode c : popedom.get("cases")) {
                JsonNode args = c.get("args");
                String label = c.get("label").asString();
                int php = c.get("code").asInt();
                JsonNode g = args.get("group");
                GroupPermissionRow group = new GroupPermissionRow(g.get("group_id").asInt(), g.get("group_type").asString(),
                        g.get("group_popedom").asString());
                JsonNode config = args.get("config");
                boolean vodWhole = config.get("user.vod_points_type").asString().equals("1");
                boolean artWhole = config.get("user.art_points_type").asString().equals("1");
                JsonNode param = args.get("param");
                JsonNode info = args.get("info");
                String what = version + " " + label + " php=" + php;
                try (ContentViewer.Scope ignored = ContentViewer.enter(group.id(), purchases)) {
                    if (args.get("flag").asString().equals("art")) {
                        ErrorCode java = article(group, param.get("id").asLong(), param.get("page").asInt(),
                                info.get("art_points").asInt(), info.get("art_points_detail").asInt(), artWhole);
                        assertThat(java).as(what).isEqualTo(php == 1 ? null : ErrorCode.ARTICLE_POINTS_REQUIRED);
                    } else {
                        int bit = args.get("popedom").asInt();
                        PlaybackEntry entry = bit == 4 ? PlaybackEntry.DOWN_PAGE
                                : bit == 5 ? PlaybackEntry.PLAYER : PlaybackEntry.PLAY_PAGE;
                        int kind = entry.down() ? info.get("vod_points_down").asInt() : info.get("vod_points_play").asInt();
                        VideoPlaybackResult r = playback(group, entry, param.get("id").asLong(), param.get("sid").asInt(),
                                param.get("nid").asInt(), info.get("vod_points").asInt(), kind,
                                args.get("trysee").asInt(), vodWhole);
                        PlaybackOutcome expected = switch (php) {
                            case 1 -> PlaybackOutcome.ALLOWED;
                            // 有意差异：外层播放页在已购买时直接放行（旧版由内页完整播放，见类注释）
                            case 3002 -> label.equals("play-trial-page-purchased") ? PlaybackOutcome.ALLOWED
                                    : PlaybackOutcome.TRIAL;
                            case 5001, 5002 -> PlaybackOutcome.TRIAL;
                            case 3003, 4001, 4003 -> PlaybackOutcome.POINTS_REQUIRED;
                            default -> PlaybackOutcome.NO_PERMISSION;
                        };
                        assertThat(r.outcome()).as(what).isEqualTo(expected);
                        assertThat(r.points()).as(what + " 价格").isEqualTo(vodWhole ? info.get("vod_points").asInt() : kind);
                    }
                }
                compared++;
            }
            assertThat(compared).as(version).isEqualTo(16);
        }
        assertThat(ContentViewer.purchases()).as("作用域关闭后一律未购买").isSameAs(ContentPurchases.NONE);
    }

    @Test
    void guestsAndPublicApiNeverSeePurchases() {
        assertThat(ContentViewer.purchases()).isSameAs(ContentPurchases.NONE);
        try (ContentViewer.Scope ignored = ContentViewer.enter(2)) {
            assertThat(ContentViewer.purchases()).isSameAs(ContentPurchases.NONE);
        }
    }

    private static VideoPlaybackResult playback(GroupPermissionRow group, PlaybackEntry entry, long id, int sid, int nid,
                                                int whole, int kind, int trysee, boolean vodWhole) {
        TypeMapper types = mock(TypeMapper.class);
        GroupMapper groups = mock(GroupMapper.class);
        when(types.selectEnabledVodTypes()).thenReturn(List.of(new TypeRow(1, "电影", "dianying", 1, 0, "", "", "", "", "",
                "")));
        when(groups.selectGroupPermission(anyInt())).thenReturn(group);
        VodMapper vods = mock(VodMapper.class);
        when(vods.selectPlayback(eq(id), anyBoolean(), any(), any())).thenReturn(new VodPlaybackRow(id, 1, "名称", "", "",
                "", 0, trysee, whole, kind, "dplayer", "第1集$https://media.example.invalid/1.mp4", "", "", 0, 0,
                1700000000L));
        VideoPlaybackService s = new VideoPlaybackService(vods, new ContentCatalogs(types, groups,
                new ContentProperties(true, false)), new PlaybackSettings(vodWhole, 0, 1));
        return s.access(id, sid, nid, entry, null);
    }

    @SuppressWarnings("unchecked")
    private static ErrorCode article(GroupPermissionRow group, long id, int page, int whole, int detail,
                                     boolean artWhole) {
        TypeMapper types = mock(TypeMapper.class);
        GroupMapper groups = mock(GroupMapper.class);
        ArtMapper arts = mock(ArtMapper.class);
        ContentLookupMapper lookup = mock(ContentLookupMapper.class);
        ObjectProvider<ContentLookupMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lookup);
        when(types.selectEnabledTypes(2)).thenReturn(List.of(new TypeRow(1, "资讯", "zixun", 1, 0, "", "", "", "", "",
                "")));
        when(groups.selectGroupPermission(anyInt())).thenReturn(group);
        when(arts.selectPublishedDetail(id)).thenReturn(LibraryFixtures.artDetail(id, 1, 0, whole > 0 ? 1 : 0,
                detail > 0 ? 1 : 0));
        when(lookup.selectArtPoints(id)).thenReturn(new ArtPointsRow(id, whole, detail));
        ArticleReadService s = new ArticleReadService(arts, new ContentCatalogs(types, groups,
                new ContentProperties(true, false, null, artWhole ? "1" : "0")), provider);
        try {
            s.getArticle(id, page, null);
            return null;
        } catch (ApiException e) {
            return e.code();
        }
    }
}

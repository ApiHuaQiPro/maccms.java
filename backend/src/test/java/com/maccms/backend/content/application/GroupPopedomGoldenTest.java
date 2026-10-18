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
import com.maccms.backend.content.infrastructure.ArtMapper;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.GroupPermissionRow;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.TypeRow;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodPlaybackRow;
import com.maccms.backend.member.MemberGolden;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * B09 页面权限按有效会员组（18 文档第 5.3 节）：逐条复放 PHP 8.0 与 7.3 黄金中旧 {@code check_user_popedom}
 * （{@code index/controller/Base.php:92-227}）的判定——游客 1、默认会员 2（含到期降入的 2 组）、VIP 3、组不存在，对播放（位 3/5、
 * 积分、试看）、下载（位 4、积分）、文章详情（位 2、积分）。{@code mac_ulog} 为空（是否已购买归 B10）。
 *
 * <p>返回码对照：1 → 放行；3002 → 试看；3003/4001/4003 → 需要积分；3001/1001 → 无权限。组不存在时旧版的分类列表本来就看不到该分类，
 * 新版按 B05 的可见性规则直接 404（同样是拒绝）。
 */
class GroupPopedomGoldenTest {

    private static final long VOD = 9;
    private static final long ART = 100;

    @Test
    void groupDecisionsMatchLegacyCheckUserPopedom() {
        for (String version : List.of("member/account-php80-golden.json", "member/account-php73-golden.json")) {
            int compared = 0;
            for (JsonNode c : MemberGolden.load(version).get("cases")) {
                String label = c.get("label").asString();
                if (!label.startsWith("popedom-") || label.startsWith("popedom-vod-detail")) {
                    continue;
                }
                JsonNode args = c.get("args");
                JsonNode group = args.get("group");
                int viewer = group.isNull() ? 9 : group.get("group_id").asInt();
                GroupPermissionRow row = group.isNull() ? null : new GroupPermissionRow(viewer,
                        group.get("group_type").asString(), group.get("group_popedom").asString());
                int php = c.get("code").asInt();
                String flag = args.get("flag").asString();
                try (ContentViewer.Scope ignored = ContentViewer.enter(viewer)) {
                    String what = version + " " + label + " php=" + php;
                    if (flag.equals("art")) {
                        int points = args.get("info").get("art_points_detail").asInt();
                        ErrorCode java = article(row, points);
                        switch (php) {
                            case 1 -> assertThat(java).as(what).isNull();
                            case 3003 -> assertThat(java).as(what).isEqualTo(ErrorCode.ARTICLE_POINTS_REQUIRED);
                            default -> assertThat(java).as(what).isEqualTo(ErrorCode.ARTICLE_NOT_FOUND);
                        }
                    } else {
                        int points = args.get("info").get("vod_points_play").asInt();
                        int trysee = args.get("trysee").asInt();
                        PlaybackEntry entry = flag.equals("play") ? PlaybackEntry.PLAY_PAGE : PlaybackEntry.DOWN_PAGE;
                        Object java = playback(row, entry, points, trysee);
                        Object expected = switch (php) {
                            case 1 -> PlaybackOutcome.ALLOWED;
                            case 3002 -> PlaybackOutcome.TRIAL;
                            case 3003, 4001, 4003 -> PlaybackOutcome.POINTS_REQUIRED;
                            default -> row == null ? ErrorCode.VIDEO_NOT_FOUND : PlaybackOutcome.NO_PERMISSION;
                        };
                        assertThat(java).as(what).isEqualTo(expected);
                    }
                }
                compared++;
            }
            assertThat(compared).as(version).isEqualTo(4 * 3 * (2 * 2 + 2 + 2));
        }
        assertThat(ContentViewer.groupId()).as("作用域关闭后回到游客").isEqualTo(1);
    }

    @Test
    void viewerScopeRestoresThePreviousGroup() {
        assertThat(ContentViewer.groupId()).isEqualTo(ContentProperties.GUEST_GROUP_ID);
        try (ContentViewer.Scope outer = ContentViewer.enter(3)) {
            assertThat(ContentViewer.groupId()).isEqualTo(3);
            try (ContentViewer.Scope inner = ContentViewer.enter(2)) {
                assertThat(ContentViewer.groupId()).isEqualTo(2);
            }
            assertThat(ContentViewer.groupId()).isEqualTo(3);
        }
        assertThat(ContentViewer.groupId()).isEqualTo(ContentProperties.GUEST_GROUP_ID);
    }

    private static Object playback(GroupPermissionRow group, PlaybackEntry entry, int points, int trysee) {
        TypeMapper types = mock(TypeMapper.class);
        GroupMapper groups = mock(GroupMapper.class);
        when(types.selectEnabledVodTypes()).thenReturn(List.of(new TypeRow(1, "电影", "dianying", 1, 0, "", "", "", "", "",
                "")));
        when(groups.selectGroupPermission(anyInt())).thenReturn(group);
        VodMapper vods = mock(VodMapper.class);
        // 旧配置 vod_points_type=0：按播放/下载各自的积分列；试看分钟取视频自己的 vod_trysee
        when(vods.selectPlayback(eq(VOD), anyBoolean(), any(), any())).thenReturn(new VodPlaybackRow(VOD, 1, "名称", "", "",
                "", 0, trysee, 0, points, "dplayer", "第1集$https://media.example.invalid/1.mp4", "", "", 0, 0,
                1700000000L));
        VideoPlaybackService s = new VideoPlaybackService(vods, new ContentCatalogs(types, groups,
                new ContentProperties(true, true)), new PlaybackSettings(false, 0, 1));
        try {
            return s.access(VOD, entry, null).outcome();
        } catch (ApiException e) {
            return e.code();
        }
    }

    private static ErrorCode article(GroupPermissionRow group, int points) {
        TypeMapper types = mock(TypeMapper.class);
        GroupMapper groups = mock(GroupMapper.class);
        ArtMapper arts = mock(ArtMapper.class);
        when(types.selectEnabledTypes(2)).thenReturn(List.of(new TypeRow(1, "资讯", "zixun", 1, 0, "", "", "", "", "",
                "")));
        when(groups.selectGroupPermission(anyInt())).thenReturn(group);
        when(arts.selectPublishedDetail(ART)).thenReturn(LibraryFixtures.artDetail(ART, 1, 0, 0, points > 0 ? 1 : 0));
        ArticleReadService s = new ArticleReadService(arts, new ContentCatalogs(types, groups,
                new ContentProperties(true, true, null, "0")));
        try {
            s.getArticle(ART);
            return null;
        } catch (ApiException e) {
            return e.code();
        }
    }
}

package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.content.application.view.VideoPlaybackResult;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.GroupPermissionRow;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.TypeRow;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodPlaybackRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * B05 播放/下载入口的判定（index/controller/Base.php:92-231、common/controller/All.php:442-563、Vod.php:86-120）。
 * 与 PHP 黄金样本的逐条对照在 PlaybackGoldenMySqlIT（真实 MySQL）；这里覆盖判定表的每个分支与配置组合。
 */
class VideoPlaybackServiceTest {

    private static final PasswordProof PROOF = new PasswordProof("a".repeat(64), "b".repeat(64));

    /**
     * @param bits 游客组对分类 6 的权限位（如 "1235"），分类 6 对游客始终可见（有位 1）
     */
    private static VideoPlaybackService service(boolean enforce, String bits, PlaybackSettings settings, VodMapper vods) {
        TypeMapper types = mock(TypeMapper.class);
        GroupMapper groups = mock(GroupMapper.class);
        when(types.selectEnabledVodTypes()).thenReturn(List.of(
                new TypeRow(1, "电影", "dianying", 1, 0, "", "", "", "", "", ""),
                new TypeRow(6, "动作片", "dongzuo", 6, 1, "", "", "", "", "", "")));
        StringBuilder six = new StringBuilder("{");
        for (char c : ("1" + bits).toCharArray()) {
            six.append(six.length() > 1 ? "," : "").append('"').append(c).append("\":\"").append(c).append('"');
        }
        six.append('}');
        when(groups.selectGroupPermission(1)).thenReturn(new GroupPermissionRow(1, ",1,6,",
                "{\"1\":{\"1\":\"1\"},\"6\":" + six + "}"));
        ContentProperties props = new ContentProperties(enforce, settings.copyrightStatus() == 2);
        return new VideoPlaybackService(vods, new ContentCatalogs(types, groups, props), settings);
    }

    private static VodPlaybackRow row(long id, int typeId, int copyright, int trySee, int pointsWhole, int pointsKind,
                                      int pwdRequired, int pwdProven) {
        return new VodPlaybackRow(id, typeId, "名称", "演员", "导演", "类型", copyright, trySee, pointsWhole, pointsKind,
                "dplayer", "第1集$https://media.example.invalid/1.mp4", "", "", pwdRequired, pwdProven, 1700000000L);
    }

    private static VodMapper vods(VodPlaybackRow r) {
        VodMapper m = mock(VodMapper.class);
        when(m.selectPlayback(eq(r.id()), anyBoolean(), any(), any())).thenReturn(r);
        return m;
    }

    private static String any() {
        return org.mockito.ArgumentMatchers.any();
    }

    @ParameterizedTest(name = "[{index}] {0} enforce={1} bits={2} vodTrysee={3} trysee={4} whole={5} kind={6} type={7} → {8}")
    @CsvSource({
            // 播放入口（vod/play 与 vod/player 的权限判定相同，Base.php:143-168）
            "PLAY_PAGE, true, 345, 0, 0, 0, 0, 1, ALLOWED",
            "PLAY_PAGE, true, 4, 0, 0, 0, 0, 1, NO_PERMISSION",
            "PLAY_PAGE, true, 5, 0, 0, 0, 0, 1, NO_PERMISSION",
            "PLAY_PAGE, true, 5, 7, 0, 0, 0, 1, TRIAL",
            "PLAY_PAGE, true, 5, 0, 3, 0, 0, 1, TRIAL",
            "PLAY_PAGE, true, 5, 0, 3, 9, 9, 1, TRIAL",
            "PLAY_PAGE, true, 3, 7, 3, 0, 0, 1, ALLOWED",
            "PLAY_PAGE, true, 3, 0, 0, 3, 0, 1, POINTS_REQUIRED",
            "PLAY_PAGE, true, 3, 0, 0, 0, 5, 1, ALLOWED",
            "PLAY_PAGE, true, 3, 0, 0, 3, 0, 0, ALLOWED",
            "PLAY_PAGE, true, 3, 0, 0, 0, 5, 0, POINTS_REQUIRED",
            "PLAY_PAGE, false, '', 7, 3, 9, 9, 1, ALLOWED",
            "PLAYER, true, 5, 7, 0, 0, 0, 1, TRIAL",
            "PLAYER, true, 2, 0, 0, 0, 0, 1, NO_PERMISSION",
            "PLAYER, true, 3, 0, 0, 3, 0, 1, POINTS_REQUIRED",
            // 下载入口（Base.php:170-177）：位 4；没有试看
            "DOWN_PAGE, true, 4, 0, 0, 0, 0, 1, ALLOWED",
            "DOWN_PAGE, true, 35, 7, 3, 0, 0, 1, NO_PERMISSION",
            "DOWN_PAGE, true, 4, 0, 0, 2, 0, 1, POINTS_REQUIRED",
            "DOWN_PAGE, true, 4, 0, 0, 0, 2, 0, POINTS_REQUIRED",
            "DOWN_PAGE, true, 4, 0, 0, 0, 2, 1, ALLOWED",
            "DOWNER, true, 4, 0, 0, 2, 0, 1, POINTS_REQUIRED",
            "DOWNER, false, '', 0, 0, 2, 2, 1, ALLOWED"})
    void permissionFollowsLegacyCheckUserPopedom(PlaybackEntry entry, boolean enforce, String bits, int vodTrySee,
                                                 int globalTrySee, int whole, int kind, int pointsType,
                                                 PlaybackOutcome expected) {
        PlaybackSettings settings = new PlaybackSettings(pointsType == 1, globalTrySee, 1);
        VideoPlaybackResult r = service(enforce, bits, settings, vods(row(9, 6, 0, vodTrySee, whole, kind, 0, 0)))
                .access(9, entry, null);
        assertThat(r.outcome()).isEqualTo(expected);
        assertThat(r.media().isPresent()).isEqualTo(expected == PlaybackOutcome.ALLOWED || expected == PlaybackOutcome.TRIAL);
        int trySee = entry.down() ? 0 : vodTrySee > 0 ? vodTrySee : globalTrySee;
        assertThat(r.trySeeMinutes()).as("player_aaaa.trysee 与是否试看无关（All.php:470-473,499）").isEqualTo(trySee);
        assertThat(r.trialMinutes()).isEqualTo(expected == PlaybackOutcome.TRIAL ? trySee : 0);
    }

    @ParameterizedTest(name = "{0} copyright_status={1} → {2}")
    @CsvSource({
            "PLAY_PAGE, 3, COPYRIGHT_RESTRICTED", "PLAY_PAGE, 4, ALLOWED", "PLAY_PAGE, 1, ALLOWED", "PLAY_PAGE, 2, ALLOWED",
            "PLAYER, 4, COPYRIGHT_RESTRICTED", "PLAYER, 3, ALLOWED", "PLAYER, 0, ALLOWED",
            "DOWN_PAGE, 3, ALLOWED", "DOWN_PAGE, 4, ALLOWED", "DOWNER, 3, ALLOWED", "DOWNER, 4, ALLOWED"})
    void copyrightOnlyAppliesToTheEntryItTargets(PlaybackEntry entry, int status, PlaybackOutcome expected) {
        VideoPlaybackResult r = service(true, "345", new PlaybackSettings(true, 0, status),
                vods(row(9, 6, 1, 0, 0, 0, 0, 0))).access(9, entry, null);
        assertThat(r.outcome()).isEqualTo(expected);
    }

    @Test
    void permissionIsCheckedBeforeCopyrightAndPassword() {
        // 旧 label_vod_play 先按权限中止（All.php:482-493），版权与密码在之后（Vod.php:89-103）
        VideoPlaybackResult r = service(true, "2", new PlaybackSettings(true, 0, 4),
                vods(row(9, 6, 1, 0, 0, 0, 1, 0))).access(9, PlaybackEntry.PLAYER, null);
        assertThat(r.outcome()).isEqualTo(PlaybackOutcome.NO_PERMISSION);
        VideoPlaybackResult c = service(true, "3", new PlaybackSettings(true, 0, 4),
                vods(row(9, 6, 1, 0, 0, 0, 1, 0))).access(9, PlaybackEntry.PLAYER, null);
        assertThat(c.outcome()).isEqualTo(PlaybackOutcome.COPYRIGHT_RESTRICTED);
    }

    @Test
    void passwordOnlyOnPlayerAndDownerAndProofIsPassedToSql() {
        VodMapper m = vods(row(9, 6, 0, 0, 0, 0, 1, 0));
        VideoPlaybackService s = service(true, "345", PlaybackSettings.REPO_DEFAULT, m);
        assertThat(s.access(9, PlaybackEntry.PLAY_PAGE, PROOF).outcome()).isEqualTo(PlaybackOutcome.ALLOWED);
        assertThat(s.access(9, PlaybackEntry.DOWN_PAGE, PROOF).outcome()).isEqualTo(PlaybackOutcome.ALLOWED);
        assertThat(s.access(9, PlaybackEntry.PLAYER, null).outcome()).isEqualTo(PlaybackOutcome.PASSWORD_REQUIRED);
        assertThat(s.access(9, PlaybackEntry.DOWNER, null).outcome()).isEqualTo(PlaybackOutcome.PASSWORD_REQUIRED);
        // 外层入口不把凭据交给 SQL
        verify(m, never()).selectPlayback(eq(9L), eq(false), eq(PROOF.salt()), eq(PROOF.fingerprint()));
        s.access(9, PlaybackEntry.PLAYER, PROOF);
        verify(m).selectPlayback(9L, false, PROOF.salt(), PROOF.fingerprint());
        s.access(9, PlaybackEntry.DOWNER, PROOF);
        verify(m).selectPlayback(9L, true, PROOF.salt(), PROOF.fingerprint());
        // SQL 判定凭据与当前密码一致时放行
        VideoPlaybackService proven = service(true, "345", PlaybackSettings.REPO_DEFAULT, vods(row(9, 6, 0, 0, 0, 0, 1, 1)));
        assertThat(proven.access(9, PlaybackEntry.PLAYER, PROOF).outcome()).isEqualTo(PlaybackOutcome.ALLOWED);
    }

    @Test
    void notFoundUnpublishedAndInvisibleCategoriesAre404() {
        VodMapper m = mock(VodMapper.class);
        VideoPlaybackService s = service(true, "345", PlaybackSettings.REPO_DEFAULT, m);
        assertThatThrownBy(() -> s.access(1, PlaybackEntry.PLAY_PAGE, null)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND);
        VideoPlaybackService hidden = service(true, "345", PlaybackSettings.REPO_DEFAULT,
                vods(row(9, 11, 0, 0, 0, 0, 0, 0)));
        assertThatThrownBy(() -> hidden.access(9, PlaybackEntry.PLAY_PAGE, null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND);
        assertThatThrownBy(() -> s.access(0, PlaybackEntry.PLAY_PAGE, null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND);
    }

    @Test
    void passwordVerificationIsSqlOnlyAndBounded() {
        VodMapper m = vods(row(9, 6, 0, 0, 0, 0, 1, 0));
        when(m.countPasswordMatch(9, false, "Ab9")).thenReturn(1);
        VideoPlaybackService s = service(true, "345", PlaybackSettings.REPO_DEFAULT, m);
        assertThat(s.verifyPassword(9, PasswordTarget.VIDEO_PLAY, "Ab9")).isTrue();
        assertThat(s.verifyPassword(9, PasswordTarget.VIDEO_PLAY, "ab9")).isFalse();
        assertThat(s.verifyPassword(9, PasswordTarget.VIDEO_DOWN, "Ab9")).isFalse();
        assertThat(s.verifyPassword(9, PasswordTarget.VIDEO_PLAY, "")).isFalse();
        assertThat(s.verifyPassword(9, PasswordTarget.VIDEO_PLAY, null)).isFalse();
        assertThat(s.verifyPassword(9, PasswordTarget.VIDEO_PLAY, "x".repeat(65))).isFalse();
        verify(m, never()).countPasswordMatch(anyLong(), anyBoolean(), eq("x".repeat(65)));
        assertThatThrownBy(() -> s.verifyPassword(9, PasswordTarget.ARTICLE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        // 分类不可见的视频不校验
        VodMapper hidden = vods(row(9, 11, 0, 0, 0, 0, 1, 0));
        when(hidden.countPasswordMatch(9, false, "Ab9")).thenReturn(1);
        assertThat(service(true, "345", PlaybackSettings.REPO_DEFAULT, hidden)
                .verifyPassword(9, PasswordTarget.VIDEO_PLAY, "Ab9")).isFalse();
        verify(hidden, never()).countPasswordMatch(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void settingsValidateWithoutEchoingValues() {
        assertThat(PlaybackSettings.from(null, true)).isEqualTo(new PlaybackSettings(true, 0, 2));
        assertThat(PlaybackSettings.from(new PlaybackProperties("0", "15", ""), false))
                .isEqualTo(new PlaybackSettings(false, 15, 1));
        assertThat(PlaybackSettings.from(new PlaybackProperties("", "", "3"), false).copyrightStatus()).isEqualTo(3);
        assertThat(PlaybackSettings.from(new PlaybackProperties("", "", "2"), true).copyrightStatus()).isEqualTo(2);
        assertThatThrownBy(() -> PlaybackSettings.from(new PlaybackProperties("", "", "3"), true))
                .hasMessageContaining("copyright-hides-detail");
        assertThatThrownBy(() -> PlaybackSettings.from(new PlaybackProperties("", "", "2"), false))
                .hasMessageContaining("不一致");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "true", "1.0", "SECRET-X"})
    void invalidPointsTypeIsRejected(String v) {
        assertThatThrownBy(() -> PlaybackSettings.from(new PlaybackProperties(v, "", ""), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("vod-points-type")
                .hasMessageNotContaining("SECRET-X");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1441", "abc", "1.5", "99999"})
    void invalidTrySeeIsRejected(String v) {
        assertThatThrownBy(() -> PlaybackSettings.from(new PlaybackProperties("", v, ""), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trysee");
    }
}

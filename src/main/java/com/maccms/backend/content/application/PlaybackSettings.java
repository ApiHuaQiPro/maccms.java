package com.maccms.backend.content.application;

import java.util.ArrayList;
import java.util.List;

/**
 * 已校验的播放/下载授权配置（{@link PlaybackProperties}）。
 *
 * @param pointsWholeVideo 积分列：{@code true} 时为 vod_points（user.vod_points_type == '1'，Base.php:110），否则为
 *                         vod_points_play / vod_points_down
 * @param trySeeMinutes    全局试看时长（分钟），0 表示关闭全局试看
 * @param copyrightStatus  app.copyright_status 的有效值 0..4
 */
public record PlaybackSettings(boolean pointsWholeVideo, int trySeeMinutes, int copyrightStatus) {

    /** 试看时长上限（分钟）：旧后台是自由文本框，这里只为排除明显错误的配置。 */
    public static final int MAX_TRYSEE = 1440;

    /** 仓库值：vod_points_type=1、trysee=0、copyright_status=1（application/extra/maccms.php:73,120-121）。 */
    public static final PlaybackSettings REPO_DEFAULT = new PlaybackSettings(true, 0, 1);

    /**
     * 不合法时抛 {@link IllegalStateException}，信息只列出配置项名称与规则，不回显取值。
     *
     * @param p                    原始配置，可为 null（全部按默认值）
     * @param copyrightHidesDetail {@code maccms.content.copyright-hides-detail}：与旧 copyright_status==2 等价的既有开关。
     *                             copyright-status 留空时据此取 2 或 1；两项都设置时必须一致，不静默改变详情页的既有行为
     */
    public static PlaybackSettings from(PlaybackProperties p, boolean copyrightHidesDetail) {
        List<String> errors = new ArrayList<>();
        String type = trim(p == null ? null : p.vodPointsType());
        boolean whole = true;
        if (!type.isEmpty()) {
            if (type.equals("0")) {
                whole = false;
            } else if (!type.equals("1")) {
                errors.add("maccms.content.playback.vod-points-type（0 或 1）");
            }
        }
        String t = trim(p == null ? null : p.trysee());
        int trysee = 0;
        if (!t.isEmpty()) {
            trysee = t.length() <= 4 && t.chars().allMatch(c -> c >= '0' && c <= '9') ? Integer.parseInt(t) : -1;
            if (trysee < 0 || trysee > MAX_TRYSEE) {
                errors.add("maccms.content.playback.trysee（0 到 " + MAX_TRYSEE + " 的整数，单位分钟）");
            }
        }
        String c = trim(p == null ? null : p.copyrightStatus());
        int status = copyrightHidesDetail ? 2 : 1;
        if (!c.isEmpty()) {
            if (c.length() == 1 && c.charAt(0) >= '0' && c.charAt(0) <= '4') {
                status = c.charAt(0) - '0';
                if ((status == 2) != copyrightHidesDetail) {
                    errors.add("maccms.content.playback.copyright-status 与 maccms.content.copyright-hides-detail 不一致"
                            + "（copyright-status=2 时 copyright-hides-detail 必须为 true，其余取值时必须为 false）");
                }
            } else {
                errors.add("maccms.content.playback.copyright-status（留空，或 0 到 4 的整数）");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("content 播放配置不合法：" + String.join("；", errors));
        }
        return new PlaybackSettings(whole, trysee, status);
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }
}

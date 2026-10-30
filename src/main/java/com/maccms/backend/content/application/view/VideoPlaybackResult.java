package com.maccms.backend.content.application.view;

import java.util.Optional;

/**
 * 播放/下载入口的判定结果与数据（B05）。视频不存在、未审核或分类不可见时不返回本类型，而是抛 VIDEO_NOT_FOUND。
 * 不是 OpenAPI 的一部分。
 *
 * @param outcome      判定结果
 * @param id           视频 id
 * @param name         vod_name（原始文本）
 * @param actor        vod_actor
 * @param director     vod_director
 * @param classes      vod_class
 * @param category     所属可见分类
 * @param updatedAt    vod_time（Unix 秒）
 * @param trySeeMinutes 旧 {@code $trysee}：vod_trysee &gt; 0 时为它，否则为全局 user.trysee（All.php:470-473）；只对播放入口有意义，
 *                     下载入口为 0。即旧 {@code player_aaaa.trysee}，与是否处于试看模式无关
 * @param points       本入口判定用的积分（B10）：{@code user.vod_points_type='1'} 时为 vod_points，否则为 vod_points_play/down；
 *                     购买确认与试看购买入口用它显示价格、生成购买表单（user.status=0 时为 0）
 * @param media        播放/下载分组；只在 ALLOWED、TRIAL 时有值
 */
public record VideoPlaybackResult(PlaybackOutcome outcome, long id, String name, String actor, String director,
                                  String classes, CategoryRefDto category, long updatedAt, int trySeeMinutes,
                                  int points, Optional<VideoMediaDto> media) {

    public VideoPlaybackResult {
        if (media.isPresent() != (outcome == PlaybackOutcome.ALLOWED || outcome == PlaybackOutcome.TRIAL)) {
            throw new IllegalArgumentException("只有 ALLOWED、TRIAL 带播放/下载数据");
        }
    }

    /** 试看模式下的计时分钟数（旧版浏览器计时），其余为 0。 */
    public int trialMinutes() {
        return outcome == PlaybackOutcome.TRIAL ? trySeeMinutes : 0;
    }
}

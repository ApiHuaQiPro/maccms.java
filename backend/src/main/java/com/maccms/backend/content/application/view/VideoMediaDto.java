package com.maccms.backend.content.application.view;

/**
 * 播放或下载的原始分组数据（vod_play_* 或 vod_down_*），只在 {@link PlaybackOutcome#ALLOWED} / {@link PlaybackOutcome#TRIAL}
 * 时随 {@link VideoPlaybackResult} 返回。由调用方用 {@code LegacyMultiValue.playSources} 按播放器/下载器配置解码；地址是否可以输出
 * 给浏览器（W2、协议白名单）由调用方决定。不是 OpenAPI 的一部分。
 *
 * @param points 本入口的积分列（vod_points_play 或 vod_points_down），即旧 {@code player_aaaa.points}（All.php:500）
 */
public record VideoMediaDto(String from, String url, String server, String note, int points) {

    @Override
    public String toString() {
        // 不输出地址
        return "VideoMediaDto[]";
    }
}

package com.maccms.backend.content.application.view;

/**
 * 视频的积分列（B10，旧 {@code vod_points}、{@code vod_points_play}、{@code vod_points_down}）。不是 OpenAPI 的一部分，只供 member
 * 模块的购买与记录使用。
 */
public record VideoPoints(long id, int whole, int play, int down) {
}

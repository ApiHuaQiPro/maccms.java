package com.maccms.backend.content.application;

/**
 * 播放/下载的四个旧入口（application/index/controller/Vod.php:86-120），各自的判定顺序不同（15 文档第 6.1、6.4 节）：
 * <ul>
 * <li>{@link #PLAY_PAGE} {@code vod/play}：权限（位 3/5、试看、积分）→ 版权 {@code copyright_status=3}；</li>
 * <li>{@link #PLAYER} {@code vod/player}：权限 → 版权 {@code copyright_status=4} → 播放密码 vod_pwd_play；</li>
 * <li>{@link #DOWN_PAGE} {@code vod/down}：权限（位 4、积分），不检查版权；</li>
 * <li>{@link #DOWNER} {@code vod/downer}：权限 → 下载密码 vod_pwd_down，不检查版权。</li>
 * </ul>
 */
public enum PlaybackEntry {
    PLAY_PAGE(false),
    PLAYER(false),
    DOWN_PAGE(true),
    DOWNER(true);

    private final boolean down;

    PlaybackEntry(boolean down) {
        this.down = down;
    }

    /** 是否为下载（vod_down_*、权限位 4）；否则为播放（vod_play_*、权限位 3/5）。 */
    public boolean down() {
        return down;
    }
}

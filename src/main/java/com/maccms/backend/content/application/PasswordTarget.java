package com.maccms.backend.content.application;

/**
 * 访问密码的用途，对应旧 {@code ajax/pwd} 的 {@code mid-type} 会话键（application/index/controller/Ajax.php:400-457）：
 * 播放 {@code 1-4}（vod_pwd_play）、下载 {@code 1-5}（vod_pwd_down）、文章 {@code 2-1}（art_pwd）。
 * 视频详情密码 {@code 1-1}（vod_pwd）不在本批（详情页仍按 MACC-14 返回 503）。
 */
public enum PasswordTarget {
    VIDEO_PLAY("v4"),
    VIDEO_DOWN("v5"),
    ARTICLE("a1");

    private final String code;

    PasswordTarget(String code) {
        this.code = code;
    }

    /** 稳定的短代码（用于 cookie 名称与签名内容）。 */
    public String code() {
        return code;
    }
}

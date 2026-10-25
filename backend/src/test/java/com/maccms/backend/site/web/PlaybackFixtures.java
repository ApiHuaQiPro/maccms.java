package com.maccms.backend.site.web;

import com.maccms.backend.content.application.view.CategoryRefDto;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.content.application.view.VideoMediaDto;
import com.maccms.backend.content.application.view.VideoPlaybackResult;
import java.util.Optional;

/** 播放/下载页测试用的合成数据（地址都在保留域名 example.invalid 下，站内地址以 /upload 开头）。 */
final class PlaybackFixtures {

    static final String LEGACY_DIR = "src/test/resources/site/play/legacy-config";
    static final String SECRET = "synthetic-test-secret-0123456789-abcdefghij";
    /** 外部播放地址（不能出现在外层页与提示页中）。 */
    static final String MEDIA = "https://media.example.invalid/v1/1.m3u8";
    static final String MEDIA2 = "https://media.example.invalid/v1/2.m3u8";

    /** 两个播放组：dplayer（3 集，第 3 集为站内 upload 地址）、videojs（协议相对地址）。 */
    static VideoMediaDto media() {
        return new VideoMediaDto("dplayer$$$videojs",
                "第1集$" + MEDIA + "#第2集$" + MEDIA2 + "#第3集$upload/vod/v1-3.mp4$$$HD$//cdn.example.invalid/v1/hd.mp4",
                "server1$$$", "备注一$$$备注二", 0);
    }

    static VideoMediaDto media(String from, String url) {
        return new VideoMediaDto(from, url, "", "", 0);
    }

    static VideoPlaybackResult result(PlaybackOutcome outcome, VideoMediaDto media, int trySee) {
        boolean withMedia = outcome == PlaybackOutcome.ALLOWED || outcome == PlaybackOutcome.TRIAL;
        return new VideoPlaybackResult(outcome, 1, "播放样本 <b>&amp;", "演员甲", "导演丙", "动作", new CategoryRefDto(6,
                "动作片", "dongzuo", 1), 1_700_000_001L, trySee, withMedia ? Optional.of(media) : Optional.empty());
    }

    static VideoPlaybackResult allowed() {
        return result(PlaybackOutcome.ALLOWED, media(), 0);
    }

    private PlaybackFixtures() {
    }
}

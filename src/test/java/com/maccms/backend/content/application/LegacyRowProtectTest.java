package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 整行读取的访问密码保护（B06，16 文档第 3.6 节）：只在详情页出现的内容（正文、分集剧情、文章分页标题与备注）随详情密码置空，
 * 播放/下载地址随各自的密码置空，布尔标记不输出。
 */
class LegacyRowProtectTest {

    private static Map<String, Object> vod(boolean pwd, boolean play, boolean down) {
        Map<String, Object> m = new HashMap<>();
        m.put("vod_id", 1L);
        m.put("vod_name", "名");
        m.put("vod_blurb", "简介");
        m.put("vod_content", "正文");
        m.put("vod_plot", 1L);
        m.put("vod_plot_name", "第1集$$$第2集");
        m.put("vod_plot_detail", "剧情1$$$剧情2");
        m.put("vod_play_url", "x$http://media.example.test/1.mp4");
        m.put("vod_down_url", "d$http://media.example.test/1.zip");
        m.put("_pwd", pwd);
        m.put("_pwd_play", play);
        m.put("_pwd_down", down);
        return m;
    }

    @Test
    void detailPasswordBlanksEveryDetailOnlyField() {
        Map<String, Object> m = LegacyRowReadService.protect(LegacyTable.VOD, vod(true, false, false));
        assertThat(m).containsEntry("vod_content", "").containsEntry("vod_plot_name", "")
                .containsEntry("vod_plot_detail", "").containsEntry("vod_play_url", "").containsEntry("vod_down_url", "");
        // 列表页也展示的字段不变
        assertThat(m).containsEntry("vod_name", "名").containsEntry("vod_blurb", "简介").containsEntry("vod_plot", 1L);
        assertThat(m).doesNotContainKeys("_pwd", "_pwd_play", "_pwd_down");
    }

    @Test
    void playAndDownloadPasswordsOnlyBlankTheirAddresses() {
        Map<String, Object> play = LegacyRowReadService.protect(LegacyTable.VOD, vod(false, true, false));
        assertThat(play).containsEntry("vod_play_url", "").containsEntry("vod_plot_detail", "剧情1$$$剧情2")
                .containsEntry("vod_content", "正文").containsEntry("vod_down_url", "d$http://media.example.test/1.zip");
        Map<String, Object> down = LegacyRowReadService.protect(LegacyTable.VOD, vod(false, false, true));
        assertThat(down).containsEntry("vod_down_url", "").containsEntry("vod_plot_name", "第1集$$$第2集")
                .containsEntry("vod_play_url", "x$http://media.example.test/1.mp4");
        Map<String, Object> open = LegacyRowReadService.protect(LegacyTable.VOD, vod(false, false, false));
        assertThat(open).containsEntry("vod_plot_detail", "剧情1$$$剧情2").containsEntry("vod_content", "正文");
    }

    @Test
    void articlePasswordBlanksContentAndPageTitlesAndNotes() {
        Map<String, Object> m = new HashMap<>();
        m.put("art_name", "文");
        m.put("art_blurb", "简介");
        m.put("art_title", "第一页$$$第二页");
        m.put("art_note", "备注1$$$备注2");
        m.put("art_content", "正文1$$$正文2");
        m.put("_pwd", true);
        Map<String, Object> out = LegacyRowReadService.protect(LegacyTable.ART, m);
        assertThat(out).containsEntry("art_content", "").containsEntry("art_title", "").containsEntry("art_note", "")
                .containsEntry("art_name", "文").containsEntry("art_blurb", "简介").doesNotContainKey("_pwd");
        m.put("_pwd", false);
        assertThat(LegacyRowReadService.protect(LegacyTable.ART, m)).containsEntry("art_title", "第一页$$$第二页");
    }

    @Test
    void columnsAbsentFromTheSelectionAreNotAdded() {
        Map<String, Object> m = new HashMap<>();
        m.put("vod_id", 1L);
        m.put("_pwd", true);
        assertThat(LegacyRowReadService.protect(LegacyTable.VOD, m)).containsOnlyKeys("vod_id");
    }
}

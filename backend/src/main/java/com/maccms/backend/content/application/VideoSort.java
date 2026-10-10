package com.maccms.backend.content.application;

import java.util.Arrays;
import java.util.Optional;

/**
 * 视频列表排序白名单，取值沿用旧 Vod::listCacheData 的 by（common/model/Vod.php:539），不含 rnd。
 * 对外参数值是 apiValue；对应列名只在 VodMapper.xml 的 choose 中出现。
 */
public enum VideoSort {
    TIME("time"),
    TIME_ADD("time_add"),
    ID("id"),
    SCORE("score"),
    HITS("hits"),
    HITS_DAY("hits_day"),
    HITS_WEEK("hits_week"),
    HITS_MONTH("hits_month"),
    UP("up"),
    DOWN("down"),
    LEVEL("level");

    private final String apiValue;

    VideoSort(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static Optional<VideoSort> fromApiValue(String value) {
        return Arrays.stream(values()).filter(s -> s.apiValue.equals(value)).findFirst();
    }
}

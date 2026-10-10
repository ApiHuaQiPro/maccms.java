package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;

/** 视频 ID 路径参数：mac_vod.vod_id 是 int unsigned，合法范围 1..4294967295。 */
public final class VideoId {

    public static final long MAX = 4_294_967_295L;

    private VideoId() {
    }

    public static long parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 10 || !raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw ApiException.invalidParameter("id", "必须是 1 到 " + MAX + " 之间的整数");
        }
        long v = Long.parseLong(raw);
        if (v < 1 || v > MAX) {
            throw ApiException.invalidParameter("id", "必须是 1 到 " + MAX + " 之间的整数");
        }
        return v;
    }
}

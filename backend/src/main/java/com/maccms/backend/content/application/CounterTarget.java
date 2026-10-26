package com.maccms.backend.content.application;

import java.util.Optional;

/**
 * 前台计数接口（{@code ajax/hits}、{@code ajax/digg}、{@code ajax/score}，B11）按 {@code mid} 对应的内容表
 * （旧 {@code mac_get_mid_code}，application/common.php:1055-1071）。{@code mid=4}（评论）的顶踩归 comment 模块，不在这里。
 */
public enum CounterTarget {
    VOD(1, "vod"),
    ART(2, "art"),
    TOPIC(3, "topic"),
    ACTOR(8, "actor"),
    ROLE(9, "role"),
    WEBSITE(11, "website");

    private final int mid;
    private final String prefix;

    CounterTarget(int mid, String prefix) {
        this.mid = mid;
        this.prefix = prefix;
    }

    /** 旧模型编号。 */
    public int mid() {
        return mid;
    }

    /** 旧列名与 cookie 名的前缀（{@code vod}、{@code art} …），例如顶踩 cookie {@code vod-digg-<id>}。 */
    public String prefix() {
        return prefix;
    }

    public static Optional<CounterTarget> ofMid(long mid) {
        for (CounterTarget t : values()) {
            if (t.mid == mid) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}

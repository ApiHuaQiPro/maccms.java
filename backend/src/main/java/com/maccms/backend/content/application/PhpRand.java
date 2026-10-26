package com.maccms.backend.content.application;

import java.util.concurrent.ThreadLocalRandom;

/** 旧代码里的 {@code rand($min, $max)}（B13：批量与字段修改的随机人气）：两端都包含；min 大于 max 时交换（PHP 的 rand 允许倒序）。 */
public final class PhpRand {

    private PhpRand() {
    }

    /** 参数按 PHP {@code intval} 取整。 */
    public static long between(String min, String max) {
        long a = PhpIntval.of(min);
        long b = PhpIntval.of(max);
        if (a > b) {
            long t = a;
            a = b;
            b = t;
        }
        return a == b ? a : ThreadLocalRandom.current().nextLong(a, b + 1);
    }
}

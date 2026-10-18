package com.maccms.backend.content.application;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 视频列表随机排序（契约 1.3.0 sort=rnd）的随机源。旧版用 PHP 的 mt_rand / array_rand（application/common/model/Vod.php:518,532），
 * 新版只要求同样的取值范围与数量，不要求与 PHP 的随机序列相同；测试与集成测试可替换为确定的实现，以覆盖每个分支并与 PHP 黄金样本对照。
 * 不用于任何安全用途。
 */
@Component
public class VideoRandom {

    /** 在 1..max（含两端）内均匀取一个整数，对应旧版 {@code mt_rand(1, $page_total)}。{@code max >= 1}。 */
    public long pageBetweenOneAnd(long max) {
        if (max < 1) {
            throw new IllegalArgumentException("max 必须 >= 1");
        }
        return ThreadLocalRandom.current().nextLong(1, max + 1);
    }

    /**
     * 从下标 0..n-1 中不放回地取 k 个，升序返回，对应旧版 {@code array_rand(range($min, $max), $k)}——它返回的是数组的<b>键</b>
     * （0..max-min），不是 ID 本身。{@code 1 <= k <= n}。用 Floyd 抽样，不按 n 分配内存。
     */
    public long[] sampleIndexes(long n, int k) {
        if (k < 1 || n < k) {
            throw new IllegalArgumentException("需要 1 <= k <= n");
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Set<Long> picked = new HashSet<>(k * 2);
        for (long j = n - k; j < n; j++) {
            long t = r.nextLong(j + 1);
            if (!picked.add(t)) {
                picked.add(j);
            }
        }
        long[] out = picked.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(out);
        return out;
    }
}

package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 默认随机源只需满足旧版的取值范围与数量（mt_rand(1, n)；array_rand(range(min, max), k) 返回 k 个互不相同、升序的键
 * 0..max-min），不要求与 PHP 的随机序列相同。PHP 侧的同一组性质由 vod_list_golden.php 在黄金样本中记录（rnd.properties）。
 */
class VideoRandomTest {

    private final VideoRandom random = new VideoRandom();

    @Test
    void pageIsWithinOneAndMaxInclusiveAndReachesBothEnds() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            long p = random.pageBetweenOneAnd(3);
            assertThat(p).isBetween(1L, 3L);
            seen.add(p);
        }
        assertThat(seen).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(random.pageBetweenOneAnd(1)).isEqualTo(1);
        assertThatThrownBy(() -> random.pageBetweenOneAnd(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sampleIsKDistinctSortedIndexesBelowN() {
        for (long n : new long[]{1000, 1001, 2001, 3001, 100_000, 4_294_967_295L}) {
            long[] s = random.sampleIndexes(n, 1000);
            assertThat(s).hasSize(1000);
            assertThat(Arrays.stream(s).distinct().count()).isEqualTo(1000);
            long[] sorted = s.clone();
            Arrays.sort(sorted);
            assertThat(s).isEqualTo(sorted);
            assertThat(s[0]).isGreaterThanOrEqualTo(0);
            assertThat(s[999]).isLessThan(n);
        }
        // k == n：取遍全部下标
        long[] all = random.sampleIndexes(1000, 1000);
        assertThat(all[0]).isZero();
        assertThat(all[999]).isEqualTo(999);
    }

    @Test
    void sampleCoversTheWholeRangeOverManyDraws() {
        long[] hits = new long[4];
        for (int i = 0; i < 50; i++) {
            for (long v : random.sampleIndexes(2001, 1000)) {
                hits[(int) (v * 4 / 2001)]++;
            }
        }
        // 每个四分位都有大量命中（均匀抽样的粗检查，不是统计检验）
        for (long h : hits) {
            assertThat(h).isGreaterThan(50 * 1000 / 8);
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> random.sampleIndexes(999, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> random.sampleIndexes(10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

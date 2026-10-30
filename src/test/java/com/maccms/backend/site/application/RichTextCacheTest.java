package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 详情正文过滤结果缓存：按 vod_id + vod_time 命中，原文变化时重算，条数与字符数有上限，并发安全。 */
class RichTextCacheTest {

    private static final RichTextSanitizer SANITIZER = RichTextSanitizer.create(ImageUrlNormalizer.create(
            new ImageUrlConfig("https", "local", "", "", ""),
            SiteSettings.from(new SiteProperties("true", "合成站", "https://www.maccms.test/", null, null, null, null,
                    null, null, null, null))));

    @Test
    void sameVersionHitsCache() {
        RichTextCache cache = new RichTextCache(SANITIZER, 8, 10_000);
        RichTextSanitizer.Result first = cache.sanitize(1, 100, "<p onclick=\"x\">正文</p><script>y</script>");
        RichTextSanitizer.Result second = cache.sanitize(1, 100, "<p onclick=\"x\">正文</p><script>y</script>");
        assertThat(second).isSameAs(first);
        assertThat(first.html().html()).isEqualTo("<p>正文</p>");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void changedTextOrVersionIsSanitizedAgain() {
        RichTextCache cache = new RichTextCache(SANITIZER, 8, 10_000);
        RichTextSanitizer.Result a = cache.sanitize(1, 100, "<p>旧</p>");
        RichTextSanitizer.Result b = cache.sanitize(1, 100, "<p>新</p>");
        assertThat(b).isNotSameAs(a);
        assertThat(b.html().html()).isEqualTo("<p>新</p>");
        assertThat(cache.sanitize(1, 101, "<p>新</p>")).isNotSameAs(b);
        assertThat(cache.sanitize(2, 101, "<p>新</p>").html().html()).isEqualTo("<p>新</p>");
    }

    @Test
    void evictsLeastRecentlyUsedByCountAndChars() {
        RichTextCache byCount = new RichTextCache(SANITIZER, 2, 10_000);
        RichTextSanitizer.Result one = byCount.sanitize(1, 1, "<p>1</p>");
        byCount.sanitize(2, 1, "<p>2</p>");
        byCount.sanitize(1, 1, "<p>1</p>");
        byCount.sanitize(3, 1, "<p>3</p>");
        assertThat(byCount.size()).isEqualTo(2);
        assertThat(byCount.sanitize(1, 1, "<p>1</p>")).as("最近用过的保留").isSameAs(one);

        RichTextCache byChars = new RichTextCache(SANITIZER, 100, 20);
        byChars.sanitize(1, 1, "<p>12345</p>");
        byChars.sanitize(2, 1, "<p>67890</p>");
        assertThat(byChars.cachedChars()).isLessThanOrEqualTo(20);
        assertThat(byChars.size()).isEqualTo(1);
        // 单条超过上限：照常返回，但不缓存
        RichTextSanitizer.Result big = byChars.sanitize(3, 1, "<p>" + "x".repeat(100) + "</p>");
        assertThat(big.html().html()).hasSize(107);
        assertThat(byChars.sanitize(3, 1, "<p>" + "x".repeat(100) + "</p>")).isNotSameAs(big);
        assertThat(byChars.cachedChars()).isLessThanOrEqualTo(20);
    }

    @Test
    void nullAndInvalidArguments() {
        RichTextCache cache = new RichTextCache(SANITIZER, 1, 1);
        assertThat(cache.sanitize(1, 1, null).outcome()).isEqualTo(RichTextSanitizer.Outcome.EMPTY);
        assertThatThrownBy(() -> new RichTextCache(null, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RichTextCache(SANITIZER, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RichTextCache(SANITIZER, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentUseIsConsistent() throws Exception {
        RichTextCache cache = new RichTextCache(SANITIZER, 16, 100_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = IntStream.range(0, 400).mapToObj(i -> pool.submit(
                    () -> cache.sanitize(i % 32, 7, "<p onclick=\"y\">正文 " + (i % 32) + "</p><script>z</script>").html().html()))
                    .toList();
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get()).isEqualTo("<p>正文 " + (i % 32) + "</p>");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(cache.size()).isLessThanOrEqualTo(16);
    }
}

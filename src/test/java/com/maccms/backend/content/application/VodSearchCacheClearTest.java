package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.maccms.backend.content.infrastructure.VodSearchMapper;
import com.maccms.backend.content.infrastructure.VodSearchWriteMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 后台"清理缓存"（B12，21 文档第 4.6 节）：删除界限 = 现在 − 分钟数 × 60，超大的分钟数不能溢出成"现在之后"。 */
class VodSearchCacheClearTest {

    private static final long NOW = 1_800_000_000L;

    private final VodSearchWriteMapper writer = mock(VodSearchWriteMapper.class);
    private final VodSearchCache cache = new VodSearchCache(mock(VodSearchMapper.class), writer,
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    @Test
    void deletesRowsOlderThanTheCacheWindow() {
        assertThat(cache.clearOld(20160)).isTrue();
        verify(writer).deleteUpdatedBefore(NOW - 20160 * 60);
    }

    @Test
    void hugeMinutesDeleteNothingLikeLegacyFloatArithmetic() {
        // 旧版 time() - PHP_INT_MAX * 60 是负的浮点数，更新时间是无符号整数，什么都不删；long 直接相乘会溢出成 now + 60 而删光
        for (long minutes : new long[] {Long.MAX_VALUE, Long.MAX_VALUE / 60 + 1, NOW / 60 + 1}) {
            assertThat(cache.clearOld(minutes)).isTrue();
        }
        verify(writer, never()).deleteUpdatedBefore(anyLong());
        assertThat(cache.clearOld(NOW / 60)).isTrue();
        verify(writer).deleteUpdatedBefore(NOW - NOW / 60 * 60);
    }
}

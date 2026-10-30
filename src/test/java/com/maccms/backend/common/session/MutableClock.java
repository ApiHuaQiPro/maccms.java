package com.maccms.backend.common.session;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** 测试用的可调时钟（毫秒精度）。 */
public final class MutableClock extends Clock {

    private final AtomicLong millis;

    public MutableClock(long epochSeconds) {
        this.millis = new AtomicLong(epochSeconds * 1000);
    }

    /** 拨到指定的秒（B13 黄金复放：每个请求前拨到 PHP 请求开始的秒）。 */
    public void set(long epochSeconds) {
        millis.set(epochSeconds * 1000);
    }

    public void advanceSeconds(long s) {
        millis.addAndGet(s * 1000);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }
}

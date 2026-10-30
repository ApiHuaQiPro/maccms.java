package com.maccms.backend.common.session;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 一个进程内会话的数据（{@link SessionStore}）。所有读改写在本对象上同步完成，同一会话的并发请求互相串行——与旧 PHP 的会话文件锁
 * （同一会话的请求排队执行）效果相同，所以 {@link #timeSpan} 的"读上次时刻、写本次时刻"不会被并发请求穿插。
 */
public final class SessionState {

    /** 旧 {@code mac_get_time_span} 在会话里没有记录时用的"上次时刻"（{@code application/common.php:311}）。 */
    public static final long LEGACY_EPOCH_SECONDS = 1228348800L;

    private final String id;
    private final Map<String, Object> values = new HashMap<>();
    private volatile long lastAccessMillis;

    SessionState(String id, long nowMillis) {
        this.id = id;
        this.lastAccessMillis = nowMillis;
    }

    public String id() {
        return id;
    }

    long lastAccessMillis() {
        return lastAccessMillis;
    }

    void touch(long nowMillis) {
        lastAccessMillis = nowMillis;
    }

    /**
     * 旧 {@code mac_get_time_span($sn)}（{@code application/common.php:306-316}）：返回"现在 − 上次时刻"（秒，可为负），
     * 并且<b>无论调用方是否放行</b>都把上次时刻改为现在。上次时刻没有记录时按 {@link #LEGACY_EPOCH_SECONDS}。
     */
    public synchronized long timeSpan(String key, long nowEpochSeconds) {
        Object last = values.get(key);
        long previous = last instanceof Long l ? l : LEGACY_EPOCH_SECONDS;
        values.put(key, nowEpochSeconds);
        return nowEpochSeconds - previous;
    }

    public synchronized void put(String key, Object value) {
        values.put(key, value);
    }

    public synchronized <T> Optional<T> get(String key, Class<T> type) {
        Object v = values.get(key);
        return type.isInstance(v) ? Optional.of(type.cast(v)) : Optional.empty();
    }

    /** 取出并移除。 */
    public synchronized <T> Optional<T> take(String key, Class<T> type) {
        Object v = values.remove(key);
        return type.isInstance(v) ? Optional.of(type.cast(v)) : Optional.empty();
    }
}

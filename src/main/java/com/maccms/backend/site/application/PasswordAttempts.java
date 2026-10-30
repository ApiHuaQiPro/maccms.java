package com.maccms.backend.site.application;

import com.maccms.backend.content.application.PasswordTarget;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 访问密码校验的进程内限流（B05，D-B05-3），代替旧版按会话 5 秒一次的频率限制（Ajax.php:416-418）。固定窗口：
 * 同一"用途 + 内容 id"每 {@value #WINDOW_SECONDS} 秒最多 {@value #PER_TARGET} 次，全部内容合计每秒最多 {@value #GLOBAL_PER_SECOND}
 * 次；超过时不查库，页面返回 429。不记录 IP、会话或密码。按内容限流意味着对同一内容的大量错误尝试会让其他访客在窗口内也被拒绝
 * （以可用性换取不保存访客标识，15 文档第 11 节）。
 */
public final class PasswordAttempts {

    static final int WINDOW_SECONDS = 60;
    static final int PER_TARGET = 10;
    static final int GLOBAL_PER_SECOND = 30;
    /** 记录的键数上限，超过时先清理过期窗口，仍然超过则拒绝新键（防止内存被大量不同 id 撑满）。 */
    static final int MAX_KEYS = 10_000;

    private record Window(long start, int count) {
    }

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile Window global = new Window(0, 0);

    public PasswordAttempts(Clock clock) {
        this.clock = clock;
    }

    /** 记一次尝试；允许时返回 true。 */
    public synchronized boolean tryAcquire(PasswordTarget target, long id) {
        long now = clock.instant().getEpochSecond();
        if (global.start() != now) {
            global = new Window(now, 0);
        }
        if (global.count() >= GLOBAL_PER_SECOND) {
            return false;
        }
        String key = target.code() + ":" + id;
        Window w = windows.get(key);
        if (w == null || now - w.start() >= WINDOW_SECONDS) {
            if (w == null && windows.size() >= MAX_KEYS) {
                evictExpired(now);
                if (windows.size() >= MAX_KEYS) {
                    return false;
                }
            }
            w = new Window(now, 0);
        }
        if (w.count() >= PER_TARGET) {
            return false;
        }
        windows.put(key, new Window(w.start(), w.count() + 1));
        global = new Window(global.start(), global.count() + 1);
        return true;
    }

    private void evictExpired(long now) {
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (now - it.next().start() >= WINDOW_SECONDS) {
                it.remove();
            }
        }
    }
}

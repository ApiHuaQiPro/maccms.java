package com.maccms.backend.common.session;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 进程内会话（B08，docs/architecture/17-b08-member-session-captcha.md 第 2.3 节）：代替旧 PHP 的 session 文件，只保存验证码与
 * 频率限制的计时（路线图 1.2 节"会话在进程内存"，不建表、不落盘）。重启即全部失效。
 *
 * <ul>
 * <li>会话标识是 128 位随机数的 32 位小写十六进制；只认本进程签发过且未过期的标识，客户端自带的未知标识一律换新
 * （旧 PHP 默认 {@code session.use_strict_mode=0} 会沿用客户端给的标识）。</li>
 * <li>空闲超过 {@link #idleTimeout()} 的会话失效（每次访问刷新）。</li>
 * <li>会话数有上限：满时先清掉过期的，仍满则淘汰最久未访问的（旧 PHP 的会话文件没有上限，这里换成有界内存）。</li>
 * </ul>
 * 线程安全：表本身由 {@code this} 保护，单个会话内的读改写由 {@link SessionState} 自己同步。
 */
public final class SessionStore {

    /** 默认空闲时限：不短于旧验证码的有效期（think-captcha {@code expire=1800}）。 */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    /** 默认上限：每个会话只有几个小条目，十万个约几十 MB。 */
    public static final int DEFAULT_MAX_SESSIONS = 100_000;

    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");

    private final Clock clock;
    private final Duration idleTimeout;
    private final int maxSessions;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, SessionState> sessions = new LinkedHashMap<>(256, 0.75f, true);

    public SessionStore(Clock clock, Duration idleTimeout, int maxSessions) {
        if (idleTimeout.isNegative() || idleTimeout.isZero() || maxSessions < 1) {
            throw new IllegalArgumentException("session limits");
        }
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.maxSessions = maxSessions;
    }

    public static SessionStore withDefaults(Clock clock) {
        return new SessionStore(clock, DEFAULT_IDLE_TIMEOUT, DEFAULT_MAX_SESSIONS);
    }

    public Clock clock() {
        return clock;
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }

    /** 按客户端给的标识找会话；格式不对、未知或已过期时为空（过期的同时移除）。找到时刷新访问时刻。 */
    public synchronized Optional<SessionState> find(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        SessionState s = sessions.get(id);
        if (s == null) {
            return Optional.empty();
        }
        long now = clock.millis();
        if (expired(s, now)) {
            sessions.remove(id);
            return Optional.empty();
        }
        s.touch(now);
        return Optional.of(s);
    }

    /** 新建会话。 */
    public synchronized SessionState create() {
        long now = clock.millis();
        if (sessions.size() >= maxSessions) {
            sessions.values().removeIf(s -> expired(s, now));
            Iterator<Map.Entry<String, SessionState>> it = sessions.entrySet().iterator();
            while (sessions.size() >= maxSessions && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        String id;
        do {
            byte[] b = new byte[16];
            random.nextBytes(b);
            id = HexFormat.of().formatHex(b);
        } while (sessions.containsKey(id));
        SessionState s = new SessionState(id, now);
        sessions.put(id, s);
        return s;
    }

    /**
     * 立即作废一个会话（B12 后台：登录时轮换标识、退出登录、D2 ⑤ 停用/改密后踢出）。之后用这个标识 {@link #find} 不到任何会话，
     * 已经拿到手的 {@link SessionState} 对象也不再属于任何会话。未知标识什么都不做。
     */
    public synchronized void invalidate(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    /** 当前会话数（含尚未清理的过期会话）。 */
    public synchronized int size() {
        return sessions.size();
    }

    private boolean expired(SessionState s, long now) {
        return now - s.lastAccessMillis() > idleTimeout.toMillis();
    }
}

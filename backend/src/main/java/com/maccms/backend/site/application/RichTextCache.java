package com.maccms.backend.site.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 详情正文过滤结果的进程内缓存（07 文档第 3.5 节建议：最坏输入单次接近 1 秒，不在每次请求时重算）。
 *
 * <p>键是视频 id + 更新时间（vod_time）；同时核对原文的长度与哈希，原文变了但 vod_time 没变时重新过滤，
 * 不会把旧结果当成新正文的结果。按最近使用淘汰，条数与缓存的过滤结果总字符数都有上限。
 * 只缓存过滤器的输出（{@link SafeHtml}），不缓存原文；不写日志。线程安全：过滤在锁外进行，并发重复过滤只多算一次。
 */
public final class RichTextCache {

    /** 默认最多缓存的条数。 */
    public static final int DEFAULT_MAX_ENTRIES = 512;
    /** 默认缓存的过滤结果总字符数上限（约 16 MB 的 UTF-16）。 */
    public static final long DEFAULT_MAX_CHARS = 8_000_000L;

    private record Key(long vodId, long updatedAt) {
    }

    private record Entry(int rawLength, int rawHash, RichTextSanitizer.Result result) {
        long chars() {
            return result.html().html().length();
        }
    }

    private final RichTextSanitizer sanitizer;
    private final int maxEntries;
    private final long maxChars;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long chars;

    public RichTextCache(RichTextSanitizer sanitizer, int maxEntries, long maxChars) {
        if (sanitizer == null || maxEntries < 1 || maxChars < 1) {
            throw new IllegalArgumentException("缓存参数不合法");
        }
        this.sanitizer = sanitizer;
        this.maxEntries = maxEntries;
        this.maxChars = maxChars;
    }

    /** 过滤一段正文，命中缓存时直接返回上次的结果。 */
    public RichTextSanitizer.Result sanitize(long vodId, long updatedAt, String raw) {
        String text = raw == null ? "" : raw;
        Key key = new Key(vodId, updatedAt);
        synchronized (this) {
            Entry e = entries.get(key);
            if (e != null && e.rawLength() == text.length() && e.rawHash() == text.hashCode()) {
                return e.result();
            }
        }
        RichTextSanitizer.Result result = sanitizer.sanitize(text);
        Entry entry = new Entry(text.length(), text.hashCode(), result);
        if (entry.chars() <= maxChars) {
            synchronized (this) {
                Entry old = entries.put(key, entry);
                if (old != null) {
                    chars -= old.chars();
                }
                chars += entry.chars();
                evict();
            }
        }
        return result;
    }

    /** 当前缓存的条数（测试用）。 */
    synchronized int size() {
        return entries.size();
    }

    /** 当前缓存的过滤结果总字符数（测试用）。 */
    synchronized long cachedChars() {
        return chars;
    }

    private void evict() {
        var it = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || chars > maxChars) && it.hasNext()) {
            Map.Entry<Key, Entry> eldest = it.next();
            chars -= eldest.getValue().chars();
            it.remove();
        }
    }
}

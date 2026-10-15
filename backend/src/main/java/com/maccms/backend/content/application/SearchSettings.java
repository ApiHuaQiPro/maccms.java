package com.maccms.backend.content.application;

import java.util.ArrayList;
import java.util.List;

/**
 * 已校验的搜索配置（09 方案第 6.3–6.4 节）。
 *
 * @param enabled       {@code maccms.content.search.enabled}：只接受 {@code true}/{@code false}，留空为 false
 * @param maxConcurrent {@code maccms.content.search.max-concurrent}：1 到连接池大小减 1 的整数，留空为 4。
 *                      给连接池中其他查询至少留一个连接，并限制同时扫描 mac_vod（MyISAM 表读锁）的长查询数量
 */
public record SearchSettings(boolean enabled, int maxConcurrent) {

    public static final int DEFAULT_MAX_CONCURRENT = 4;
    public static final SearchSettings DISABLED = new SearchSettings(false, DEFAULT_MAX_CONCURRENT);

    /** 不合法时抛 {@link IllegalStateException}，信息只列出配置项名称与规则，不回显取值。 */
    public static SearchSettings from(ContentProperties.Search search, int poolSize) {
        List<String> errors = new ArrayList<>();
        boolean enabled = false;
        String e = search == null ? null : search.enabled();
        if (e != null && !e.isEmpty()) {
            if ("true".equals(e)) {
                enabled = true;
            } else if (!"false".equals(e)) {
                errors.add("maccms.content.search.enabled 只能是 true 或 false");
            }
        }
        int max = DEFAULT_MAX_CONCURRENT;
        String m = search == null ? null : search.maxConcurrent();
        if (m != null && !m.isEmpty()) {
            max = m.length() <= 4 && m.chars().allMatch(c -> c >= '0' && c <= '9') ? Integer.parseInt(m) : -1;
        }
        if (max < 1 || max > poolSize - 1) {
            errors.add("maccms.content.search.max-concurrent 必须是 1 到连接池大小减 1（MACCMS_DB_POOL_SIZE − 1）之间的整数");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("content 搜索配置不合法：" + String.join("；", errors));
        }
        return new SearchSettings(enabled, max);
    }
}

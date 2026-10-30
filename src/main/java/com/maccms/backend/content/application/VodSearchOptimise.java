package com.maccms.backend.content.application;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 旧配置 {@code app.vod_search_optimise}（application/common/model/VodSearch.php:69-78 {@code isFrontendEnabled}）：按 {@code |} 拆分后含
 * {@code frontend} 时，前台视频列表使用搜索缓存。每次查询时读取（旧配置按修改时间热加载，与旧版每个请求读配置一致）；未配置旧配置目录、
 * 没有这一项或读取失败时为关闭。
 */
public final class VodSearchOptimise {

    private static final Logger log = LoggerFactory.getLogger(VodSearchOptimise.class);

    private final LegacyConfigService legacy;

    public VodSearchOptimise(LegacyConfigService legacy) {
        this.legacy = legacy;
    }

    public boolean frontendEnabled() {
        if (legacy == null || !legacy.isConfigured()) {
            return false;
        }
        try {
            String v = PhpValues.text(legacy.read(LegacyConfigFile.MACCMS).path("app", "vod_search_optimise"));
            return Arrays.asList(v.split("\\|", -1)).contains("frontend");
        } catch (LegacyConfigException e) {
            log.warn("vod_search_optimise unreadable, treated as off: {}", SafeExceptionLog.typeChain(e));
            return false;
        }
    }

    /**
     * 旧 {@code VodSearch::getResultCacheMinutes}（application/common/model/VodSearch.php:129-138）：{@code app.vod_search_optimise_cache_minutes}
     * 按 {@code (int)} 大于 0 时取它，否则 {@value #DEFAULT_CACHE_MINUTES}（14 天）。未配置旧配置目录或读取失败时取默认值。
     */
    public long cacheMinutes() {
        if (legacy == null || !legacy.isConfigured()) {
            return DEFAULT_CACHE_MINUTES;
        }
        try {
            long v = PhpIntval.of(PhpValues.text(legacy.read(LegacyConfigFile.MACCMS)
                    .path("app", "vod_search_optimise_cache_minutes")));
            return v > 0 ? v : DEFAULT_CACHE_MINUTES;
        } catch (LegacyConfigException e) {
            log.warn("vod_search_optimise_cache_minutes unreadable, default used: {}", SafeExceptionLog.typeChain(e));
            return DEFAULT_CACHE_MINUTES;
        }
    }

    /** 旧版的默认缓存分钟数（14 天）。 */
    public static final long DEFAULT_CACHE_MINUTES = 20160;
}

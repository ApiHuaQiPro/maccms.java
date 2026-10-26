package com.maccms.backend.content.application;

/**
 * 后台"清理缓存"中属于内容模块的部分（B12，docs/architecture/21-b12-admin-framework.md 第 4.6 节）：旧
 * {@code admin/controller/Index.php:158-167} 先清运行目录与缓存驱动（新版没有这些，见 21 文档），再调用
 * {@code model('VodSearch')->clearOldResult(true)} 删除过期的视频搜索缓存。
 */
public class ContentCacheMaintenance {

    private final VodSearchCache cache;
    private final VodSearchOptimise optimise;

    public ContentCacheMaintenance(VodSearchCache cache, VodSearchOptimise optimise) {
        this.cache = cache;
        this.optimise = optimise;
    }

    /**
     * 删除过期的视频搜索缓存行（mac_vod_search，按旧配置的缓存分钟数）。
     *
     * @return 可写数据源关闭时为 false
     */
    public boolean clearVodSearchCache() {
        return cache.clearOld(optimise.cacheMinutes());
    }
}

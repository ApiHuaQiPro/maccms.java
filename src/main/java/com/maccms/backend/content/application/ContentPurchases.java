package com.maccms.backend.content.application;

/**
 * 当前观看者的"已购买"查询（B10，docs/architecture/19-b10-member-finance.md 第 5.1 节；D-B09-11）：旧版会员组 &lt; 3 访问收费数据时
 * 查 {@code mac_ulog} 是否有完全相同的购买记录（{@code index/controller/Base.php:118-227}）。由 site 的会员拦截器随
 * {@link ContentViewer} 一起设置，实现在 member 模块（只读）；游客、公开 API 与没有设置时为 {@link #NONE}（一律未购买）。
 */
@FunctionalInterface
public interface ContentPurchases {

    /** 一律未购买。 */
    ContentPurchases NONE = key -> false;

    /** 是否已有与 {@code key} 七项完全相同（会员由实现方确定）的 {@code mac_ulog} 记录。 */
    boolean purchased(PurchaseKey key);
}

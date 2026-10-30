package com.maccms.backend.content.application.view;

/**
 * 游客访问播放/下载入口的判定结果（B05，15 文档第 6.4 节；队长裁定 D-B05-2～4）。不是 OpenAPI 的一部分：只供 site 页面使用，
 * 公开 API 不提供播放/下载地址。
 */
public enum PlaybackOutcome {
    /** 有播放（位 3）或下载（位 4）权限，且不需要积分。 */
    ALLOWED,
    /** 无播放权限、有试看权限（位 5）且试看时长 &gt; 0（Base.php:147-148），旧版浏览器计时边界。 */
    TRIAL,
    /** 无对应权限（且不能试看）：Base.php:144-145（3001）、:171-172（1001）。 */
    NO_PERMISSION,
    /** 游客且积分列 &gt; 0：Base.php:150-167（3003 扣积分确认）、:175-176（4001）。购买归 B10。 */
    POINTS_REQUIRED,
    /** 实际命中的版权限制：vod_copyright=1 且 copyright_status=3（vod/play）或 4（vod/player），Vod.php:89-91、:98-100。 */
    COPYRIGHT_RESTRICTED,
    /** 播放/下载密码非空且没有有效凭据：Vod.php:101-103（1-4）、:116-118（1-5）。 */
    PASSWORD_REQUIRED
}

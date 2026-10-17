package com.maccms.backend.content.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 播放/下载授权相关的旧配置对应项（B05，docs/architecture/15-b05-readonly-pages.md 第 6 节）。与 {@link ContentProperties} 相同，
 * content 只读本应用的环境变量、不读旧配置文件；部署时按旧 {@code application/extra/maccms.php} 的对应值设置。
 * 按原始字符串绑定，校验在 {@link PlaybackSettings#from} 中进行，错误只报配置项名称。
 *
 * @param vodPointsType   对应 user.vod_points_type：{@code 1} 按 vod_points（每数据），{@code 0} 按 vod_points_play/vod_points_down
 *                        （每集），index/controller/Base.php:108-112；留空按仓库值 {@code 1}
 * @param trysee          对应 user.trysee：全局试看时长（分钟），{@code 0} 关闭全局试看（application/lang/zh-cn.php:1229）；留空按仓库值 0
 * @param copyrightStatus 对应 app.copyright_status（0 关闭、1 提示、2 内容页跳转、3 播放页跳转、4 iframe 播放页跳转，
 *                        admin/view/system/config.html:393-397）；留空时按 {@code maccms.content.copyright-hides-detail} 取 2 或 1
 */
@ConfigurationProperties(prefix = "maccms.content.playback")
public record PlaybackProperties(String vodPointsType, String trysee, String copyrightStatus) {
}

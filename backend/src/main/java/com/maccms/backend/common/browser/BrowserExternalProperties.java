package com.maccms.backend.common.browser;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 浏览器侧外部地址放行参数 {@code maccms.browser-external.*}（B02，路线图第 1.3、4.2 节）。
 * 部署级参数：只来自本应用的环境变量，不是站点配置项，不出现在后台，不写入 {@code application/extra/*.php}。
 * 按原始字符串绑定，校验在 {@link BrowserExternalAccess#from} 中进行，错误只报配置项名称。
 *
 * @param w1 W1 播放器解析接口、缓冲/预加载页与外部播放器脚本，默认打开
 * @param w2 W2 外链图片与外部播放/下载地址，默认打开
 * @param w3 W3 支付网关跳转，默认关闭（B24 前只能为 false）
 */
@ConfigurationProperties(prefix = "maccms.browser-external")
public record BrowserExternalProperties(String w1, String w2, String w3) {
}

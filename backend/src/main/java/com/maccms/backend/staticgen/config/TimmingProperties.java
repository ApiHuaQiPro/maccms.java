package com.maccms.backend.staticgen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务入口的部署参数（B16，25 文档第 7.1 节；D2 ③）。只来自本应用的环境变量，不是站点配置项，不出现在后台，不写进
 * {@code application/extra/*.php}。
 *
 * @param enabled 是否提供 {@code api.php/timming/index}（{@code MACCMS_TIMMING_ENABLED}，默认 false）
 * @param token   外部触发口令（{@code MACCMS_TIMMING_TOKEN}，可留空；留空时只接受本机请求）。请求在头
 *                {@code X-Maccms-Timming-Token} 中携带；不接受查询串里的口令（会进访问日志）
 */
@ConfigurationProperties(prefix = "maccms.timming")
public record TimmingProperties(boolean enabled, String token) {

    @Override
    public String toString() {
        // 不输出口令
        return "TimmingProperties[enabled=" + enabled + ", token=" + (token == null || token.isEmpty() ? "(none)" : "(set)") + "]";
    }
}

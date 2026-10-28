package com.maccms.backend.staticgen.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次生成请求的输出（旧 Make.php 逐行 {@code echo}/{@code mac_echo} 的内容 + 结尾的 {@code mac_jump} 或 {@code error()}）。
 *
 * @param html  已输出的 HTML（旧版写死的标记原样；数据——名称、地址——已转义）
 * @param jump  后台入口结尾的跳转（定时任务入口没有）；没有时为 null
 * @param error 中途 {@code $this->error(...)} 的提示（已按语言包取好）；没有时为 null
 */
public record MakeOutput(String html, Jump jump, String error) {

    /**
     * 旧 {@code mac_jump($url, $sec)}。
     *
     * @param action  后台动作（{@code make/make}、{@code make/opt}、{@code make/index}）；为 null 时跳回来源页（{@code referer}）
     * @param query   查询参数（{@code http_build_query} 的顺序）；空表表示不带参数
     * @param seconds 等待秒数
     * @param referer {@code action} 为 null 时的来源页地址
     */
    public record Jump(String action, Map<String, String> query, int seconds, String referer) {
        public Jump {
            query = query == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(query));
        }
    }
}

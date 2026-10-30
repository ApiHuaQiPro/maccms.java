package com.maccms.backend.content.application.view;

/**
 * 一张截图（mac_screenshot_list，application/common.php:1415-1432）：index 是在 {@code #} 分隔序列中的位置（从 1 起，空段占位）；
 * 没有地址部分时 name 为该序号、url 为整段。url 原样返回库内值。
 */
public record ScreenshotDto(int index, String name, String url) {
}

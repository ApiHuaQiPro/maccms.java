package com.maccms.backend.content.application.view;

/** 一条分集剧情（mac_plot_list，application/common.php:1320-1341）：index 从 1 起，detail 按位置对齐，缺少时为 ""。 */
public record PlotDto(int index, String name, String detail) {
}

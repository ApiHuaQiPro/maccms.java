package com.maccms.backend.content.infrastructure;

/**
 * 视频分集剧情（VodPlotMapper.xml）：plotNames/plotDetails 为 {@code $$$} 分隔的原文（vod_plot_name/vod_plot_detail），
 * 另带与视频详情相同的可见性判定列（分类、是否有观看密码、版权标记）。
 */
public record VodPlotRow(Long id, Integer categoryId, String plotNames, String plotDetails,
                         Integer detailPasswordRequired, Integer copyright) {
}

package com.maccms.backend.content.application.view;

import java.util.List;

/** GET /api/v1/videos/{id}/plots 的响应：视频的分集剧情。 */
public record VideoPlotListResponse(long videoId, List<PlotDto> items) {
}

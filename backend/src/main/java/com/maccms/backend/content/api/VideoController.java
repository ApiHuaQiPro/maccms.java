package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.api.dto.VideoDetailDto;
import com.maccms.backend.content.api.dto.VideoSummaryDto;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.VideoListQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 契约：docs/contracts/content-read.openapi.yaml 中 /api/v1/videos 与 /api/v1/videos/{id}。 */
@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {

    private final ContentReadService service;

    public VideoController(ContentReadService service) {
        this.service = service;
    }

    /** 参数以字符串接收，由 VideoListQuery 统一校验，保证错误响应格式固定。 */
    @GetMapping
    public PageResponse<VideoSummaryDto> list(@RequestParam(required = false) String categoryId,
                                              @RequestParam(required = false) String sort,
                                              @RequestParam(required = false) String order,
                                              @RequestParam(required = false) String page,
                                              @RequestParam(required = false) String pageSize) {
        return service.listVideos(VideoListQuery.parse(categoryId, sort, order, page, pageSize));
    }

    @GetMapping("/{id}")
    public VideoDetailDto detail(@PathVariable String id) {
        return service.getVideo(VideoId.parse(id));
    }
}

package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.view.VideoDetailDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契约：docs/contracts/content-read.openapi.yaml 中 /api/v1/videos 与 /api/v1/videos/{id}。 */
@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {

    private final ContentReadService service;

    public VideoController(ContentReadService service) {
        this.service = service;
    }

    /**
     * 参数以字符串读取，由 VideoListQuery 统一校验，保证错误响应格式固定。既有 5 个参数先校验，规则与 1.0.0 相同
     * （多次出现时与原 {@code @RequestParam String} 一样逗号连接后按原规则判错）；契约 1.1.0 的 6 个搜索/筛选参数随后
     * 由 {@link VideoFilter#parse} 按固定顺序校验，重复与非法 UTF-8 由 {@link RawQueryParams} 从请求中显式判断。
     * 不用 {@code @RequestParam}：它会在进入方法前触发容器解析，非法编码的新参数就无法得到 400。
     */
    @GetMapping
    public PageResponse<VideoSummaryDto> list(HttpServletRequest request) {
        RawQueryParams params = RawQueryParams.of(request);
        VideoListQuery query = VideoListQuery.parse(params.joined("categoryId"), params.joined("sort"),
                params.joined("order"), params.joined("page"), params.joined("pageSize"));
        VideoFilter filter = VideoFilter.parse(params.filterParam("keyword"), params.filterParam("area"),
                params.filterParam("lang"), params.filterParam("year"), params.filterParam("level"),
                params.filterParam("classes"), service.searchEnabled());
        return service.listVideos(query.withFilter(filter));
    }

    @GetMapping("/{id}")
    public VideoDetailDto detail(@PathVariable String id) {
        return service.getVideo(VideoId.parse(id));
    }
}

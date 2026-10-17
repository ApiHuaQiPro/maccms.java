package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.TopicReadService;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.view.TopicDetailDto;
import com.maccms.backend.content.application.view.TopicSummaryDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契约 1.2.0：/api/v1/topics、/api/v1/topics/{id}。 */
@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {

    private final TopicReadService service;

    public TopicController(TopicReadService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<TopicSummaryDto> list(HttpServletRequest request) {
        RawQueryParams params = RawQueryParams.of(request);
        return service.listTopics(ContentListQuery.parse(ContentKind.TOPIC, params::joined));
    }

    @GetMapping("/{id}")
    public TopicDetailDto detail(@PathVariable String id) {
        return service.getTopic(VideoId.parse(id));
    }
}

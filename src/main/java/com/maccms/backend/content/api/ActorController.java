package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.view.ActorDetailDto;
import com.maccms.backend.content.application.view.ActorSummaryDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契约 1.2.0：/api/v1/actor-categories、/api/v1/actors、/api/v1/actors/{id}。 */
@RestController
@RequestMapping("/api/v1")
public class ActorController {

    private final ActorReadService service;

    public ActorController(ActorReadService service) {
        this.service = service;
    }

    @GetMapping("/actor-categories")
    public CategoryListResponse categories() {
        return service.listCategories();
    }

    @GetMapping("/actors")
    public PageResponse<ActorSummaryDto> list(HttpServletRequest request) {
        RawQueryParams params = RawQueryParams.of(request);
        return service.listActors(ContentListQuery.parse(ContentKind.ACTOR, params::joined));
    }

    @GetMapping("/actors/{id}")
    public ActorDetailDto detail(@PathVariable String id) {
        return service.getActor(VideoId.parse(id));
    }
}

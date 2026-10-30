package com.maccms.backend.content.api;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.view.CategoryListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契约：docs/contracts/content-read.openapi.yaml 中 /api/v1/video-categories。 */
@RestController
@RequestMapping("/api/v1/video-categories")
public class VideoCategoryController {

    private final ContentReadService service;

    public VideoCategoryController(ContentReadService service) {
        this.service = service;
    }

    @GetMapping
    public CategoryListResponse list() {
        return service.listCategories();
    }
}

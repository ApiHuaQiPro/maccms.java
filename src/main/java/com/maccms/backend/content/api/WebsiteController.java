package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.WebsiteDetailDto;
import com.maccms.backend.content.application.view.WebsiteSummaryDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契约 1.2.0：/api/v1/website-categories、/api/v1/websites、/api/v1/websites/{id}。 */
@RestController
@RequestMapping("/api/v1")
public class WebsiteController {

    private final WebsiteReadService service;

    public WebsiteController(WebsiteReadService service) {
        this.service = service;
    }

    @GetMapping("/website-categories")
    public CategoryListResponse categories() {
        return service.listCategories();
    }

    @GetMapping("/websites")
    public PageResponse<WebsiteSummaryDto> list(HttpServletRequest request) {
        RawQueryParams params = RawQueryParams.of(request);
        return service.listWebsites(ContentListQuery.parse(ContentKind.WEBSITE, params::joined));
    }

    @GetMapping("/websites/{id}")
    public WebsiteDetailDto detail(@PathVariable String id) {
        return service.getWebsite(VideoId.parse(id));
    }
}

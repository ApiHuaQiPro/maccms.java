package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.LinkReadService;
import com.maccms.backend.content.application.view.LinkDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契约 1.2.0：/api/v1/links（友情链接没有详情页）。 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkReadService service;

    public LinkController(LinkReadService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<LinkDto> list(HttpServletRequest request) {
        RawQueryParams params = RawQueryParams.of(request);
        return service.listLinks(ContentListQuery.parse(ContentKind.LINK, params::joined));
    }
}

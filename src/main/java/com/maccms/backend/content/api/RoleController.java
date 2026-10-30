package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.RoleReadService;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.view.RoleDetailDto;
import com.maccms.backend.content.application.view.RoleSummaryDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契约 1.2.0：/api/v1/roles、/api/v1/roles/{id}。 */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleReadService service;

    public RoleController(RoleReadService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<RoleSummaryDto> list(HttpServletRequest request) {
        RawQueryParams params = RawQueryParams.of(request);
        return service.listRoles(ContentListQuery.parse(ContentKind.ROLE, params::joined));
    }

    @GetMapping("/{id}")
    public RoleDetailDto detail(@PathVariable String id) {
        return service.getRole(VideoId.parse(id));
    }
}

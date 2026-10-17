package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.LinkDto;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.LinkMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/** 友情链接只读查询（契约 1.2.0，旧 Link::listCacheData，application/common/model/Link.php:31-82）。友链没有状态与权限，列出全部行。 */
@Service
public class LinkReadService {

    private final LinkMapper linkMapper;

    public LinkReadService(LinkMapper linkMapper) {
        this.linkMapper = linkMapper;
    }

    public PageResponse<LinkDto> listLinks(ContentListQuery query) {
        if (query.kind() != ContentKind.LINK) {
            throw new IllegalArgumentException("kind");
        }
        return read(() -> {
            ContentCriteria criteria = query.filter().toCriteria();
            long total = linkMapper.count(criteria);
            if (total == 0 || query.offset() >= total) {
                return PageResponse.of(List.of(), query.page(), query.pageSize(), total);
            }
            List<LinkDto> items = linkMapper.selectPage(criteria, query.sort(), query.descending(), query.offset(),
                    query.pageSize()).stream()
                    .map(r -> new LinkDto(nz(r.id()), nz(r.type()), str(r.name()), nz(r.sort()), str(r.url()),
                            str(r.logo()), nz(r.addedAt()), nz(r.updatedAt()))).toList();
            return PageResponse.of(items, query.page(), query.pageSize(), total);
        });
    }
}

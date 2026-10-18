package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.dec;
import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.RoleDetailDto;
import com.maccms.backend.content.application.view.RoleSummaryDto;
import com.maccms.backend.content.application.view.RoleVideoDto;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.RoleDetailRow;
import com.maccms.backend.content.infrastructure.RoleMapper;
import com.maccms.backend.content.infrastructure.RoleSummaryRow;
import com.maccms.backend.content.infrastructure.SortColumn;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodRefRow;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 角色只读查询（契约 1.2.0）。可见性与旧版相同，只有 role_status=1（Role.php:153，旧角色页不做权限检查）。
 * 所属视频（role_rid）只在视频已审核且所属分类可见时给出摘要，否则为 {@link RoleVideoDto#NONE}；旧版不看视频状态
 * （Role.php:41-51、:296-303），新版不暴露隐藏视频的名称。
 */
@Service
public class RoleReadService {

    private final RoleMapper roleMapper;
    private final VodMapper vodMapper;
    private final ContentCatalogs catalogs;

    public RoleReadService(RoleMapper roleMapper, VodMapper vodMapper, ContentCatalogs catalogs) {
        this.roleMapper = roleMapper;
        this.vodMapper = vodMapper;
        this.catalogs = catalogs;
    }

    public PageResponse<RoleSummaryDto> listRoles(ContentListQuery query) {
        return read(() -> page(query, query.filter().toCriteria()));
    }

    /** 一页（异常转换由调用方的 read 负责）。 */
    PageResponse<RoleSummaryDto> page(ContentListQuery query, ContentCriteria criteria) {
        if (query.kind() != ContentKind.ROLE) {
            throw new IllegalArgumentException("kind");
        }
        return LibrarySlice.page(slice(criteria), query.sort(), query.descending(), query.page(), query.pageSize());
    }

    /** 条件下的切片（角色没有分类）；B05 的 LibrarySearchService 以 SSR 内部条件调用。 */
    LibrarySlice<RoleSummaryDto> slice(ContentCriteria criteria) {
        return new LibrarySlice<>() {
            @Override
            public long count() {
                return roleMapper.countPublished(criteria);
            }

            @Override
            public List<RoleSummaryDto> rows(SortColumn sort, boolean descending, long offset, int limit) {
                List<RoleSummaryRow> rows = roleMapper.selectPublishedPage(criteria, sort, descending, offset, limit);
                Set<Long> videoIds = new LinkedHashSet<>();
                rows.forEach(r -> {
                    if (nz(r.videoId()) > 0) {
                        videoIds.add(r.videoId());
                    }
                });
                Map<Long, RoleVideoDto> refs = videoRefs(videoIds);
                return rows.stream().map(r -> toSummary(r, refs.getOrDefault(nz(r.videoId()), RoleVideoDto.NONE)))
                        .toList();
            }
        };
    }

    public RoleDetailDto getRole(long id) {
        return read(() -> {
            RoleDetailRow row = roleMapper.selectPublishedDetail(id);
            if (row == null) {
                throw new ApiException(ErrorCode.ROLE_NOT_FOUND, "角色不存在");
            }
            long videoId = nz(row.videoId());
            RoleVideoDto video = videoId > 0 ? videoRefs(Set.of(videoId)).getOrDefault(videoId, RoleVideoDto.NONE)
                    : RoleVideoDto.NONE;
            return toDetail(row, video);
        });
    }

    private Map<Long, RoleVideoDto> videoRefs(Set<Long> ids) {
        Map<Long, RoleVideoDto> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        CategoryCatalog catalog = catalogs.vodCatalog(catalogs.viewer());
        for (VodRefRow r : vodMapper.selectPublishedRefs(ids)) {
            int categoryId = nz(r.categoryId());
            if (catalog.isVisible(categoryId)) {
                out.put(r.id(), new RoleVideoDto(r.id(), str(r.name()), str(r.enName()), categoryId));
            }
        }
        return out;
    }

    private static RoleSummaryDto toSummary(RoleSummaryRow r, RoleVideoDto video) {
        return new RoleSummaryDto(r.id(), video, str(r.name()), str(r.enName()), str(r.letter()), str(r.color()),
                str(r.actor()), str(r.remarks()), str(r.pic()), nz(r.sort()), nz(r.level()), dec(r.score()),
                nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()),
                nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()));
    }

    private static RoleDetailDto toDetail(RoleDetailRow r, RoleVideoDto video) {
        return new RoleDetailDto(r.id(), video, str(r.name()), str(r.enName()), str(r.letter()), str(r.color()),
                str(r.actor()), str(r.remarks()), str(r.pic()), nz(r.sort()), nz(r.level()), dec(r.score()),
                nz(r.scoreAll()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()),
                nz(r.up()), nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()), str(r.content()));
    }
}

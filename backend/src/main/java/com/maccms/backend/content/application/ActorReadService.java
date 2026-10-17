package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.dec;
import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.ref;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.ActorDetailDto;
import com.maccms.backend.content.application.view.ActorSummaryDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.infrastructure.ActorDetailRow;
import com.maccms.backend.content.infrastructure.ActorMapper;
import com.maccms.backend.content.infrastructure.ActorSummaryRow;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 演员只读查询（契约 1.2.0）。可见性：actor_status=1，且所属演员分类（type_mid=8）按 {@link CategoryCatalog} 规则可见；
 * 详情另检查游客详情权限（旧 label_actor_detail → check_user_popedom(type, 2)；mac_actor 没有积分列，不涉及积分）。
 */
@Service
public class ActorReadService {

    static final int TYPE_MID = 8;

    private final ActorMapper actorMapper;
    private final ContentCatalogs catalogs;

    public ActorReadService(ActorMapper actorMapper, ContentCatalogs catalogs) {
        this.actorMapper = actorMapper;
        this.catalogs = catalogs;
    }

    public CategoryListResponse listCategories() {
        return read(() -> catalogs.tree(TYPE_MID));
    }

    public PageResponse<ActorSummaryDto> listActors(ContentListQuery query) {
        return read(() -> page(query, query.filter().toCriteria()));
    }

    /** 一页（异常转换由调用方的 read 负责）；B05 的 LibrarySearchService 以并入 LibraryMatch 的条件调用。 */
    PageResponse<ActorSummaryDto> page(ContentListQuery query, ContentCriteria criteria) {
        if (query.kind() != ContentKind.ACTOR) {
            throw new IllegalArgumentException("kind");
        }
        CategoryCatalog catalog = catalogs.catalog(TYPE_MID, catalogs.guest());
        Set<Integer> typeIds = ArticleReadService.typeIds(catalog, query.categoryId());
        if (typeIds.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), 0);
        }
        long total = actorMapper.countPublished(typeIds, criteria);
        if (total == 0 || query.offset() >= total) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), total);
        }
        List<ActorSummaryDto> items = actorMapper.selectPublishedPage(typeIds, criteria, query.sort(),
                query.descending(), query.offset(), query.pageSize()).stream()
                .map(r -> toSummary(r, catalog)).toList();
        return PageResponse.of(items, query.page(), query.pageSize(), total);
    }

    public ActorDetailDto getActor(long id) {
        return read(() -> {
            ActorDetailRow row = actorMapper.selectPublishedDetail(id);
            if (row == null) {
                throw notFound();
            }
            GuestPermission guest = catalogs.guest();
            CategoryCatalog catalog = catalogs.catalog(TYPE_MID, guest);
            int categoryId = nz(row.categoryId());
            if (!catalog.isVisible(categoryId) || !catalogs.guestMayViewDetail(guest, categoryId)) {
                throw notFound();
            }
            return toDetail(row, catalog);
        });
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.ACTOR_NOT_FOUND, "演员不存在");
    }

    private static ActorSummaryDto toSummary(ActorSummaryRow r, CategoryCatalog catalog) {
        return new ActorSummaryDto(r.id(), str(r.name()), str(r.enName()), str(r.alias()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.sex()), str(r.pic()), str(r.blurb()),
                str(r.remarks()), str(r.area()), str(r.height()), str(r.weight()), str(r.birthday()),
                str(r.birthArea()), str(r.blood()), str(r.starSign()), str(r.school()), str(r.works()), str(r.tag()),
                str(r.classes()), nz(r.level()), dec(r.score()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()),
                nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()));
    }

    private static ActorDetailDto toDetail(ActorDetailRow r, CategoryCatalog catalog) {
        return new ActorDetailDto(r.id(), str(r.name()), str(r.enName()), str(r.alias()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.sex()), str(r.pic()), str(r.blurb()),
                str(r.remarks()), str(r.area()), str(r.height()), str(r.weight()), str(r.birthday()),
                str(r.birthArea()), str(r.blood()), str(r.starSign()), str(r.school()), str(r.works()), str(r.tag()),
                str(r.classes()), nz(r.level()), dec(r.score()), nz(r.scoreAll()), nz(r.scoreNum()), nz(r.hits()),
                nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()),
                nz(r.addedAt()), str(r.content()));
    }
}

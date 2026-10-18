package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.dec;
import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.ref;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.WebsiteDetailDto;
import com.maccms.backend.content.application.view.WebsiteSummaryDto;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.SortColumn;
import com.maccms.backend.content.infrastructure.WebsiteDetailRow;
import com.maccms.backend.content.infrastructure.WebsiteMapper;
import com.maccms.backend.content.infrastructure.WebsiteSummaryRow;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 网址只读查询（契约 1.2.0）。可见性：website_status=1，且所属网址分类（type_mid=11）按 {@link CategoryCatalog} 规则可见；
 * 详情另检查游客详情权限（旧 label_website_detail → check_user_popedom(type, 2)；mac_website 没有积分列）。
 */
@Service
public class WebsiteReadService {

    static final int TYPE_MID = 11;

    private final WebsiteMapper websiteMapper;
    private final ContentCatalogs catalogs;

    public WebsiteReadService(WebsiteMapper websiteMapper, ContentCatalogs catalogs) {
        this.websiteMapper = websiteMapper;
        this.catalogs = catalogs;
    }

    public CategoryListResponse listCategories() {
        return read(() -> catalogs.tree(TYPE_MID));
    }

    public PageResponse<WebsiteSummaryDto> listWebsites(ContentListQuery query) {
        return read(() -> page(query, query.filter().toCriteria()));
    }

    /** 一页（异常转换由调用方的 read 负责）。 */
    PageResponse<WebsiteSummaryDto> page(ContentListQuery query, ContentCriteria criteria) {
        if (query.kind() != ContentKind.WEBSITE) {
            throw new IllegalArgumentException("kind");
        }
        return LibrarySlice.page(slice(query.categoryId(), criteria), query.sort(), query.descending(), query.page(),
                query.pageSize());
    }

    /** 分类范围（categoryId 为 null 时是全部可见分类）与条件下的切片；B05 的 LibrarySearchService 以 SSR 内部条件调用。 */
    LibrarySlice<WebsiteSummaryDto> slice(Integer categoryId, ContentCriteria criteria) {
        CategoryCatalog catalog = catalogs.catalog(TYPE_MID, catalogs.guest());
        Set<Integer> typeIds = ArticleReadService.typeIds(catalog, categoryId);
        if (typeIds.isEmpty()) {
            return LibrarySlice.empty();
        }
        return new LibrarySlice<>() {
            @Override
            public long count() {
                return websiteMapper.countPublished(typeIds, criteria);
            }

            @Override
            public List<WebsiteSummaryDto> rows(SortColumn sort, boolean descending, long offset, int limit) {
                return websiteMapper.selectPublishedPage(typeIds, criteria, sort, descending, offset, limit).stream()
                        .map(r -> toSummary(r, catalog)).toList();
            }
        };
    }

    public WebsiteDetailDto getWebsite(long id) {
        return read(() -> {
            WebsiteDetailRow row = websiteMapper.selectPublishedDetail(id);
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
        return new ApiException(ErrorCode.WEBSITE_NOT_FOUND, "网址不存在");
    }

    private static WebsiteSummaryDto toSummary(WebsiteSummaryRow r, CategoryCatalog catalog) {
        return new WebsiteSummaryDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), nz(r.sort()), str(r.url()), str(r.pic()),
                str(r.logo()), str(r.area()), str(r.lang()), str(r.tag()), str(r.classes()), str(r.blurb()),
                str(r.remarks()), nz(r.level()), dec(r.score()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()),
                nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.referer()), nz(r.refererDay()),
                nz(r.refererWeek()), nz(r.refererMonth()), nz(r.updatedAt()), nz(r.addedAt()));
    }

    private static WebsiteDetailDto toDetail(WebsiteDetailRow r, CategoryCatalog catalog) {
        return new WebsiteDetailDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), nz(r.sort()), str(r.url()), str(r.pic()),
                str(r.logo()), str(r.area()), str(r.lang()), str(r.tag()), str(r.classes()), str(r.blurb()),
                str(r.remarks()), nz(r.level()), dec(r.score()), nz(r.scoreAll()), nz(r.scoreNum()), nz(r.hits()),
                nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.referer()),
                nz(r.refererDay()), nz(r.refererWeek()), nz(r.refererMonth()), nz(r.updatedAt()), nz(r.addedAt()),
                str(r.content()), LegacyMultiValue.screenshots(r.screenshots()));
    }
}

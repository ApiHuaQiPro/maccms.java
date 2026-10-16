package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.dec;
import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.ref;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.ArticleDetailDto;
import com.maccms.backend.content.application.view.ArticleSummaryDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.infrastructure.ArtDetailRow;
import com.maccms.backend.content.infrastructure.ArtMapper;
import com.maccms.backend.content.infrastructure.ArtSummaryRow;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 文章只读查询（契约 1.2.0）。只调用 Mapper 的 select。可见性：art_status=1，且所属文章分类（type_mid=2）按
 * {@link CategoryCatalog} 规则可见；详情另按旧 label_art_detail 检查游客详情权限、积分与访问密码
 * （docs/architecture/11-content-read-completion.md 第 4.3 节）。数据库连接故障与执行时限超时归为 503（{@link ContentReadService#read}）。
 */
@Service
public class ArticleReadService {

    static final int TYPE_MID = 2;

    private final ArtMapper artMapper;
    private final ContentCatalogs catalogs;

    public ArticleReadService(ArtMapper artMapper, ContentCatalogs catalogs) {
        this.artMapper = artMapper;
        this.catalogs = catalogs;
    }

    public CategoryListResponse listCategories() {
        return read(() -> catalogs.tree(TYPE_MID));
    }

    public PageResponse<ArticleSummaryDto> listArticles(ContentListQuery query) {
        if (query.kind() != ContentKind.ARTICLE) {
            throw new IllegalArgumentException("kind");
        }
        return read(() -> {
            CategoryCatalog catalog = catalogs.catalog(TYPE_MID, catalogs.guest());
            Set<Integer> typeIds = typeIds(catalog, query.categoryId());
            if (typeIds.isEmpty()) {
                return PageResponse.of(List.of(), query.page(), query.pageSize(), 0);
            }
            ContentCriteria criteria = query.filter().toCriteria();
            long total = artMapper.countPublished(typeIds, criteria);
            if (total == 0 || query.offset() >= total) {
                return PageResponse.of(List.of(), query.page(), query.pageSize(), total);
            }
            List<ArticleSummaryDto> items = artMapper.selectPublishedPage(typeIds, criteria, query.sort(),
                    query.descending(), query.offset(), query.pageSize()).stream()
                    .map(r -> toSummary(r, catalog)).toList();
            return PageResponse.of(items, query.page(), query.pageSize(), total);
        });
    }

    public ArticleDetailDto getArticle(long id) {
        return read(() -> {
            ArtDetailRow row = artMapper.selectPublishedDetail(id);
            if (row == null) {
                throw notFound();
            }
            GuestPermission guest = catalogs.guest();
            CategoryCatalog catalog = catalogs.catalog(TYPE_MID, guest);
            int categoryId = nz(row.categoryId());
            if (!catalog.isVisible(categoryId) || !catalogs.guestMayViewDetail(guest, categoryId)) {
                throw notFound();
            }
            // 旧版游客（group_id<3）访问 art_points/art_points_detail>0 的文章时改为显示扣积分确认页（index/controller/Base.php:105-112、:118-142）；
            // user.status=0 时不检查（:115-117）
            if (catalogs.enforceGuestPopedom() && nz(row.pointsRequired()) != 0) {
                throw new ApiException(ErrorCode.ARTICLE_POINTS_REQUIRED, "该文章需要积分才能查看");
            }
            // 旧版 art_pwd 非空且本会话未验证时显示密码页（index/controller/Art.php:56-61）
            if (nz(row.detailPasswordRequired()) != 0) {
                throw new ApiException(ErrorCode.ARTICLE_PASSWORD_REQUIRED, "该文章需要访问密码");
            }
            return toDetail(row, catalog);
        });
    }

    /** 本分类加上可见的直接子分类（旧 type 条件，application/common/model/Art.php:271-286）；不传分类时为全部可见分类。 */
    static Set<Integer> typeIds(CategoryCatalog catalog, Integer categoryId) {
        if (categoryId == null) {
            return catalog.allIds();
        }
        if (!catalog.isVisible(categoryId)) {
            throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
        }
        return catalog.idsForFilter(categoryId);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.ARTICLE_NOT_FOUND, "文章不存在");
    }

    /** 列表项（专题关联文章经 TopicReadService 复用）。 */
    static ArticleSummaryDto toSummary(ArtSummaryRow r, CategoryCatalog catalog) {
        return new ArticleSummaryDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.source()), str(r.author()), str(r.tag()),
                str(r.classes()), str(r.pic()), str(r.picThumb()), str(r.picSlide()), str(r.blurb()), str(r.remarks()),
                nz(r.level()), dec(r.score()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()),
                nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()),
                nz(r.detailPasswordRequired()) != 0);
    }

    private static ArticleDetailDto toDetail(ArtDetailRow r, CategoryCatalog catalog) {
        return new ArticleDetailDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.source()), str(r.author()), str(r.tag()),
                str(r.classes()), str(r.pic()), str(r.picThumb()), str(r.picSlide()), str(r.blurb()), str(r.remarks()),
                nz(r.level()), dec(r.score()), nz(r.scoreAll()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()),
                nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()),
                LegacyMultiValue.articlePages(r.pageTitles(), r.pageNotes(), r.content()),
                LegacyMultiValue.screenshots(r.screenshots()));
    }
}

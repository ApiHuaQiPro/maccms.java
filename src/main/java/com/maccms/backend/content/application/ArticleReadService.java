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
import com.maccms.backend.content.infrastructure.ArtPointsRow;
import com.maccms.backend.content.infrastructure.ContentLookupMapper;
import com.maccms.backend.content.infrastructure.ArtSummaryRow;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.SortColumn;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectProvider<ContentLookupMapper> lookup;

    /** @param lookup 积分值查找（B10）；没有这个 Mapper（只装配了部分 Bean 的测试）时收费文章一律按未购买 */
    @Autowired
    public ArticleReadService(ArtMapper artMapper, ContentCatalogs catalogs, ObjectProvider<ContentLookupMapper> lookup) {
        this.artMapper = artMapper;
        this.catalogs = catalogs;
        this.lookup = lookup;
    }

    ArticleReadService(ArtMapper artMapper, ContentCatalogs catalogs) {
        this(artMapper, catalogs, null);
    }

    public CategoryListResponse listCategories() {
        return read(() -> catalogs.tree(TYPE_MID));
    }

    public PageResponse<ArticleSummaryDto> listArticles(ContentListQuery query) {
        return read(() -> page(query, query.filter().toCriteria()));
    }

    /** 一页（异常转换由调用方的 read 负责）。 */
    PageResponse<ArticleSummaryDto> page(ContentListQuery query, ContentCriteria criteria) {
        if (query.kind() != ContentKind.ARTICLE) {
            throw new IllegalArgumentException("kind");
        }
        return LibrarySlice.page(slice(query.categoryId(), criteria), query.sort(), query.descending(), query.page(),
                query.pageSize());
    }

    /** 分类范围（categoryId 为 null 时是全部可见分类）与条件下的切片；B05 的 LibrarySearchService 以 SSR 内部条件调用。 */
    LibrarySlice<ArticleSummaryDto> slice(Integer categoryId, ContentCriteria criteria) {
        CategoryCatalog catalog = catalogs.catalog(TYPE_MID, catalogs.viewer());
        Set<Integer> typeIds = typeIds(catalog, categoryId);
        if (typeIds.isEmpty()) {
            return LibrarySlice.empty();
        }
        return new LibrarySlice<>() {
            @Override
            public long count() {
                return artMapper.countPublished(typeIds, criteria);
            }

            @Override
            public List<ArticleSummaryDto> rows(SortColumn sort, boolean descending, long offset, int limit) {
                return artMapper.selectPublishedPage(typeIds, criteria, sort, descending, offset, limit).stream()
                        .map(r -> toSummary(r, catalog)).toList();
            }
        };
    }

    public ArticleDetailDto getArticle(long id) {
        return getArticle(id, null);
    }

    /**
     * 文章详情；有访问密码的文章只在凭据与当前密码一致时返回（B05，D-B05-3，{@link PasswordProof}），否则仍是
     * ARTICLE_PASSWORD_REQUIRED。判定顺序 404 → 积分 → 密码不变。
     *
     * @param proof 访问密码凭据（site 的签名票据校验通过后），可为 null
     */
    public ArticleDetailDto getArticle(long id, PasswordProof proof) {
        return getArticle(id, 1, proof);
    }

    /**
     * 同 {@link #getArticle(long, PasswordProof)}，带当前页码（旧 {@code $param['page']}）：按页收费（{@code user.art_points_type != '1'}）
     * 时购买记录按页匹配（B10，19 文档第 5.1 节）。
     */
    public ArticleDetailDto getArticle(long id, int page, PasswordProof proof) {
        return read(() -> {
            ArtDetailRow row = artMapper.selectPublishedDetail(id);
            if (row == null) {
                throw notFound();
            }
            GroupPermission viewer = catalogs.viewer();
            CategoryCatalog catalog = catalogs.catalog(TYPE_MID, viewer);
            int categoryId = nz(row.categoryId());
            if (!catalog.isVisible(categoryId) || !catalogs.mayViewDetail(viewer, categoryId)) {
                throw notFound();
            }
            // 旧版会员组 group_id<3（游客与默认会员）访问需积分的文章时改为显示扣积分确认页（index/controller/Base.php:105-112、
            // :118-142）：积分列由 user.art_points_type 决定，'1' 时为 art_points（整篇），否则为 art_points_detail（按页，:108-111）；
            // user.status=0 时不检查（:115-117）。B05 起按配置取列；B09 起按观看者的会员组（组 ≥ 3 不检查）；B10 起已购买时放行
            // （mac_ulog：mid=2、type=1、rid、sid=页码（整篇收费时 0）、nid=0、points，Base.php:121-136）
            if (catalogs.enforceGuestPopedom() && charged(row) && viewer.groupId() < 3 && !purchased(id, page)) {
                throw new ApiException(ErrorCode.ARTICLE_POINTS_REQUIRED, "该文章需要积分才能查看");
            }
            // 旧版 art_pwd 非空且本会话未验证时显示密码页（index/controller/Art.php:56-61）
            if (nz(row.detailPasswordRequired()) != 0 && (proof == null
                    || artMapper.countPasswordProven(id, proof.salt(), proof.fingerprint()) == 0)) {
                throw new ApiException(ErrorCode.ARTICLE_PASSWORD_REQUIRED, "该文章需要访问密码");
            }
            return toDetail(row, catalog);
        });
    }

    /**
     * 已审核文章按 {@code user.art_points_type} 取的价格（B10：购买确认页显示价格、生成购买表单）；不存在或未审核时为空。
     */
    public Optional<Integer> requiredPoints(long id) {
        return read(() -> artMapper.selectPublishedDetail(id) == null ? Optional.<Integer>empty()
                : Optional.of(pointsValue(id)));
    }

    /** 旧 ajax_buy_popedom 与 check_user_popedom 的购买记录条件（整篇收费时 sid 为 0）。 */
    public PurchaseKey purchaseKey(long id, int page, int points) {
        return new PurchaseKey(2, 1, id, catalogs.artPointsWholeArticle() ? 0 : page, 0, points);
    }

    /** 游客与没有设置查询时不读积分值。 */
    private boolean purchased(long id, int page) {
        ContentPurchases purchases = ContentViewer.purchases();
        return purchases != ContentPurchases.NONE && purchases.purchased(purchaseKey(id, page, pointsValue(id)));
    }

    /** 按 user.art_points_type 取的积分列 &gt; 0（ArtMapper 只给出布尔值）。 */
    private boolean charged(ArtDetailRow row) {
        return (catalogs.artPointsWholeArticle() ? nz(row.pointsRequired()) : nz(row.pointsDetailRequired())) != 0;
    }

    /** 积分值（B10）：只在收费时经 ContentLookupMapper 读出，用于购买记录匹配与确认页的价格。 */
    private int pointsValue(long id) {
        ContentLookupMapper m = lookup == null ? null : lookup.getIfAvailable();
        ArtPointsRow r = m == null ? null : m.selectArtPoints(id);
        if (r == null) {
            return -1;
        }
        return catalogs.artPointsWholeArticle() ? nz(r.whole()) : nz(r.detail());
    }

    /**
     * 文章访问密码校验（B05）：文章已审核、分类可见、art_pwd 非空且与 candidate 逐字节相等。candidate 为空或超过
     * {@link VideoPlaybackService#MAX_PASSWORD_LENGTH} 个码点时直接为 false（不查库）。只返回布尔值，密码列不读入内存。
     */
    public boolean verifyPassword(long id, String candidate) {
        if (id < 1 || candidate == null || candidate.isEmpty()
                || candidate.codePointCount(0, candidate.length()) > VideoPlaybackService.MAX_PASSWORD_LENGTH) {
            return false;
        }
        return read(() -> {
            ArtDetailRow row = artMapper.selectPublishedDetail(id);
            if (row == null || !catalogs.catalog(TYPE_MID, catalogs.viewer()).isVisible(nz(row.categoryId()))) {
                return false;
            }
            return artMapper.countPasswordMatch(id, candidate) > 0;
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

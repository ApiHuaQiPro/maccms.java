package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.api.dto.CategoryDto;
import com.maccms.backend.content.api.dto.CategoryExtendDto;
import com.maccms.backend.content.api.dto.CategoryListResponse;
import com.maccms.backend.content.api.dto.CategoryRefDto;
import com.maccms.backend.content.api.dto.VideoDetailDto;
import com.maccms.backend.content.api.dto.VideoSummaryDto;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.TypeRow;
import com.maccms.backend.content.infrastructure.VodDetailRow;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodSummaryRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * content 模块的只读查询。只调用 Mapper 的 select，不写任何表，不写缓存。
 * 可见性规则见 CategoryCatalog 与 docs/architecture/01-batch1-content-read-plan.md 第 4 节。
 */
@Service
public class ContentReadService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TypeMapper typeMapper;
    private final GroupMapper groupMapper;
    private final VodMapper vodMapper;
    private final ContentProperties properties;

    public ContentReadService(TypeMapper typeMapper, GroupMapper groupMapper, VodMapper vodMapper,
                              ContentProperties properties) {
        this.typeMapper = typeMapper;
        this.groupMapper = groupMapper;
        this.vodMapper = vodMapper;
        this.properties = properties;
    }

    public CategoryListResponse listCategories() {
        CategoryCatalog catalog = loadCatalog(loadGuest());
        return new CategoryListResponse(toTree(catalog, catalog.roots()));
    }

    public PageResponse<VideoSummaryDto> listVideos(VideoListQuery query) {
        CategoryCatalog catalog = loadCatalog(loadGuest());
        Set<Integer> typeIds;
        if (query.categoryId() != null) {
            if (!catalog.isVisible(query.categoryId())) {
                throw new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
            }
            typeIds = catalog.idsForFilter(query.categoryId());
        } else {
            typeIds = catalog.allIds();
        }
        if (typeIds.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), 0);
        }
        long total = vodMapper.countPublished(typeIds);
        if (total == 0 || query.offset() >= total) {
            return PageResponse.of(List.of(), query.page(), query.pageSize(), total);
        }
        List<VodSummaryRow> rows = vodMapper.selectPublishedPage(typeIds, query.sort(), query.descending(),
                query.offset(), query.pageSize());
        List<VideoSummaryDto> items = rows.stream().map(r -> toSummary(r, catalog)).toList();
        return PageResponse.of(items, query.page(), query.pageSize(), total);
    }

    public VideoDetailDto getVideo(long id) {
        VodDetailRow row = vodMapper.selectPublishedDetail(id);
        if (row == null) {
            throw notFound();
        }
        GuestPermission guest = loadGuest();
        CategoryCatalog catalog = loadCatalog(guest);
        int categoryId = nz(row.categoryId());
        if (!catalog.isVisible(categoryId)) {
            throw notFound();
        }
        // 旧详情页对游客检查"详情"权限位 2（common/controller/All.php:408-414 → index/controller/Base.php:170-173）
        if (properties.enforceGuestPopedom() && !guest.allows(categoryId, GuestPermission.DETAIL)) {
            throw notFound();
        }
        // 旧详情页：vod_pwd 非空且本会话未验证时改为显示密码页（index/controller/Vod.php:61-63）
        if (nz(row.detailPasswordRequired()) != 0) {
            throw new ApiException(ErrorCode.VIDEO_PASSWORD_REQUIRED, "该视频详情需要观看密码");
        }
        // 旧详情页：vod_copyright=1 且 copyright_status=2 时改为显示版权页（index/controller/Vod.php:58-60）
        if (properties.copyrightHidesDetail() && nz(row.copyright()) == 1) {
            throw new ApiException(ErrorCode.VIDEO_COPYRIGHT_RESTRICTED, "该视频因版权原因不提供详情");
        }
        return toDetail(row, catalog);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在");
    }

    private GuestPermission loadGuest() {
        if (!properties.enforceGuestPopedom()) {
            return GuestPermission.from(null);
        }
        return GuestPermission.from(groupMapper.selectGroupPermission(ContentProperties.GUEST_GROUP_ID));
    }

    private CategoryCatalog loadCatalog(GuestPermission guest) {
        return CategoryCatalog.build(typeMapper.selectEnabledVodTypes(), guest, properties.enforceGuestPopedom());
    }

    private static List<CategoryDto> toTree(CategoryCatalog catalog, List<TypeRow> rows) {
        return rows.stream().map(r -> new CategoryDto(
                r.id(), str(r.name()), str(r.enName()), nz(r.sort()), nz(r.parentId()),
                str(r.title()), str(r.keywords()), str(r.description()), str(r.logo()), str(r.pic()),
                parseExtend(r.extendJson()),
                toTree(catalog, catalog.childrenOf(r.id())))).toList();
    }

    static CategoryExtendDto parseExtend(String json) {
        JsonNode node = null;
        if (json != null && !json.isBlank()) {
            try {
                node = JSON.readTree(json);
            } catch (JacksonException e) {
                node = null;
            }
        }
        if (node == null || !node.isObject()) {
            return new CategoryExtendDto("", "", "", "", "", "", "", "");
        }
        return new CategoryExtendDto(text(node, "class"), text(node, "area"), text(node, "lang"),
                text(node, "year"), text(node, "star"), text(node, "director"), text(node, "state"),
                text(node, "version"));
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || v.isContainer()) {
            return "";
        }
        return v.asString();
    }

    private static CategoryRefDto ref(CategoryCatalog catalog, int categoryId) {
        TypeRow t = catalog.get(categoryId);
        return new CategoryRefDto(t.id(), str(t.name()), str(t.enName()), nz(t.parentId()));
    }

    private static VideoSummaryDto toSummary(VodSummaryRow r, CategoryCatalog catalog) {
        return new VideoSummaryDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.classes()), str(r.tag()), str(r.pic()),
                str(r.picThumb()), str(r.picSlide()), str(r.actor()), str(r.director()), str(r.blurb()),
                str(r.remarks()), str(r.area()), str(r.lang()), str(r.year()), str(r.version()), str(r.state()),
                str(r.serial()), nz(r.total()), nz(r.isEnd()) == 1, nz(r.level()), nz(r.copyright()) == 1,
                dec(r.score()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()),
                nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()),
                nz(r.detailPasswordRequired()) != 0);
    }

    private static VideoDetailDto toDetail(VodDetailRow r, CategoryCatalog catalog) {
        return new VideoDetailDto(r.id(), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), ref(catalog, nz(r.categoryId())), str(r.classes()), str(r.tag()), str(r.pic()),
                str(r.picThumb()), str(r.picSlide()), str(r.actor()), str(r.director()), str(r.writer()),
                str(r.behind()), str(r.blurb()), str(r.remarks()), str(r.pubdate()), str(r.area()), str(r.lang()),
                str(r.year()), str(r.version()), str(r.state()), str(r.tv()), str(r.weekday()), str(r.duration()),
                str(r.serial()), nz(r.total()), nz(r.isEnd()) == 1, nz(r.level()), nz(r.copyright()) == 1,
                dec(r.score()), nz(r.scoreAll()), nz(r.scoreNum()), nz(r.doubanId()), dec(r.doubanScore()),
                nz(r.hits()), nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()),
                nz(r.updatedAt()), nz(r.addedAt()), str(r.content()));
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static BigDecimal dec(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(1) : v;
    }
}

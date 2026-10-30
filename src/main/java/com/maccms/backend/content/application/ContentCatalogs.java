package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.CategoryRefDto;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.TypeRow;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 契约 1.2.0 各服务共用的分类可见性与取值工具。可见性规则与视频分类完全相同（{@link CategoryCatalog}，
 * docs/architecture/01-batch1-content-read-plan.md 第 4.1 节），只是按模型 type_mid（2 文章、8 演员、11 网址）取分类。
 */
@Component
class ContentCatalogs {

    private final TypeMapper typeMapper;
    private final GroupMapper groupMapper;
    private final ContentProperties properties;

    ContentCatalogs(TypeMapper typeMapper, GroupMapper groupMapper, ContentProperties properties) {
        this.typeMapper = typeMapper;
        this.groupMapper = groupMapper;
        this.properties = properties;
    }

    boolean enforceGuestPopedom() {
        return properties.enforceGuestPopedom();
    }

    /** user.art_points_type（{@link ContentProperties#artPointsWholeArticle()}）。 */
    boolean artPointsWholeArticle() {
        return properties.artPointsWholeArticle();
    }

    /**
     * 当前请求观看者的会员组权限（B09：{@link ContentViewer}，没有设置时为游客组 1）。user.status=0 时不判定，不查库。
     */
    GroupPermission viewer() {
        if (!properties.enforceGuestPopedom()) {
            return GroupPermission.from(null);
        }
        return GroupPermission.from(groupMapper.selectGroupPermission(ContentViewer.groupId()));
    }

    CategoryCatalog catalog(int typeMid, GroupPermission viewer) {
        return CategoryCatalog.build(typeMapper.selectEnabledTypes(typeMid), viewer, properties.enforceGuestPopedom());
    }

    /** 视频分类（type_mid=1），与 ContentReadService 使用同一条 SQL。 */
    CategoryCatalog vodCatalog(GroupPermission viewer) {
        return CategoryCatalog.build(typeMapper.selectEnabledVodTypes(), viewer, properties.enforceGuestPopedom());
    }

    CategoryListResponse tree(int typeMid) {
        CategoryCatalog catalog = catalog(typeMid, viewer());
        return new CategoryListResponse(ContentReadService.toTree(catalog, catalog.roots()));
    }

    /** 启用权限校验时，观看者的会员组对该分类有"详情"权限位 2（index/controller/Base.php:92-100、:115-121）。 */
    boolean mayViewDetail(GroupPermission viewer, int categoryId) {
        return !properties.enforceGuestPopedom() || viewer.allows(categoryId, GroupPermission.DETAIL);
    }

    // ------------------------------------------------------------------ 取值

    static CategoryRefDto ref(CategoryCatalog catalog, int categoryId) {
        TypeRow t = catalog.get(categoryId);
        return new CategoryRefDto(t.id(), str(t.name()), str(t.enName()), nz(t.parentId()));
    }

    static String str(String s) {
        return s == null ? "" : s;
    }

    static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    static long nz(Long v) {
        return v == null ? 0L : v;
    }

    static BigDecimal dec(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(1) : v;
    }
}

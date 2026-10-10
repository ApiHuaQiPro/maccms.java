package com.maccms.backend.content.application;

import com.maccms.backend.content.infrastructure.TypeRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次请求内的"公开可见视频分类"快照。可见 = 同时满足：
 * <ol>
 *   <li>type_mid=1 且 type_status=1（由 TypeMapper SQL 保证；旧 Type.php:130,189）；</li>
 *   <li>启用游客权限校验时，游客组对该分类有"列表"权限位 1（旧 Base.php:97-100,170-173，label_type 调用处 All.php:181）；</li>
 *   <li>上级分类链一直可见直到顶级（type_pid=0）。旧 mac_list_to_tree 也会丢弃找不到上级的节点（common.php:476-500）。</li>
 * </ol>
 */
public final class CategoryCatalog {

    private static final int MAX_DEPTH = 16;

    private final Map<Integer, TypeRow> visible;
    private final Map<Integer, List<TypeRow>> childrenByParent;

    private CategoryCatalog(Map<Integer, TypeRow> visible) {
        this.visible = visible;
        Map<Integer, List<TypeRow>> children = new LinkedHashMap<>();
        for (TypeRow row : visible.values()) {
            children.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        }
        this.childrenByParent = children;
    }

    /** rows 须已按 type_sort、type_id 升序（TypeMapper 保证），快照内保持该顺序。 */
    public static CategoryCatalog build(List<TypeRow> rows, GuestPermission guest, boolean enforceGuestPopedom) {
        Map<Integer, TypeRow> candidates = new LinkedHashMap<>();
        for (TypeRow row : rows) {
            if (!enforceGuestPopedom || guest.allows(row.id(), GuestPermission.LIST)) {
                candidates.put(row.id(), row);
            }
        }
        Map<Integer, TypeRow> visible = new LinkedHashMap<>();
        for (TypeRow row : candidates.values()) {
            if (reachesRoot(row, candidates)) {
                visible.put(row.id(), row);
            }
        }
        return new CategoryCatalog(Collections.unmodifiableMap(visible));
    }

    private static boolean reachesRoot(TypeRow row, Map<Integer, TypeRow> candidates) {
        TypeRow current = row;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            int parentId = current.parentId() == null ? 0 : current.parentId();
            if (parentId == 0) {
                return true;
            }
            current = candidates.get(parentId);
            if (current == null) {
                return false;
            }
        }
        return false; // 环或过深的数据视为不可见
    }

    public boolean isVisible(int id) {
        return visible.containsKey(id);
    }

    public TypeRow get(int id) {
        return visible.get(id);
    }

    public Set<Integer> allIds() {
        return visible.keySet();
    }

    /**
     * 按分类筛选视频时使用的分类 ID：本分类加上它的可见直接子分类。
     * 与旧 Vod::listCacheData 的 type 条件一致（type_id 或 type_pid 命中，common/model/Vod.php:341-350）。
     */
    public Set<Integer> idsForFilter(int id) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (!visible.containsKey(id)) {
            return ids;
        }
        ids.add(id);
        for (TypeRow child : childrenByParent.getOrDefault(id, List.of())) {
            ids.add(child.id());
        }
        return ids;
    }

    public List<TypeRow> roots() {
        return childrenByParent.getOrDefault(0, List.of());
    }

    public List<TypeRow> childrenOf(int id) {
        return childrenByParent.getOrDefault(id, List.of());
    }
}

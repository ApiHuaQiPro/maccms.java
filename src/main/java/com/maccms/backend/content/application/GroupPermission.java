package com.maccms.backend.content.application;

import com.maccms.backend.content.infrastructure.GroupPermissionRow;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 某个会员组对分类的权限，语义照搬旧 check_user_popedom 的判定（index/controller/Base.php:97-100）：
 * group_type（形如 ",1,6,"）包含该分类 ID，且 group_popedom[分类ID][权限位] 按 PHP 的 !empty 为真。
 * 权限位：1 列表、2 详情、3 播放、4 下载、5 试看（admin/view/group/info.html:55-60）。
 *
 * <p>B05 起只用于游客组（原名 GuestPermission）；B09 起按当前请求的有效会员组取（{@link ContentViewer}，
 * docs/architecture/18-b09-member-account.md 第 5.3 节）。{@link #groupId()} 是组行的 group_id，组不存在时为 0——旧版此时
 * {@code $group} 为空，{@code $group['group_id']<3} 成立、{@code ==1}/{@code ==2} 都不成立（Base.php:131-218）。
 */
public final class GroupPermission {

    public static final String LIST = "1";
    public static final String DETAIL = "2";
    /** 播放、下载、试看（B05，Base.php:143-227）。 */
    public static final String PLAY = "3";
    public static final String DOWN = "4";
    public static final String TRY_SEE = "5";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final GroupPermission NONE = new GroupPermission(0, Set.of(), null);

    private final int groupId;
    private final Set<String> typeIds;
    private final JsonNode popedom;

    private GroupPermission(int groupId, Set<String> typeIds, JsonNode popedom) {
        this.groupId = groupId;
        this.typeIds = typeIds;
        this.popedom = popedom;
    }

    /** 组不存在或字段无法解析时没有任何权限（旧代码取不到组时 strpos 判定为 false）。 */
    public static GroupPermission from(GroupPermissionRow row) {
        if (row == null) {
            return NONE;
        }
        return of(row.id() == null ? 0 : row.id(), row.typeIds(), row.popedomJson());
    }

    /**
     * 由组行的原文构造（B10：会员中心"我的权限"页按会员自己的组判定，旧 {@code User::popedom}，common/model/User.php:528-537）。
     *
     * @param typeIds     {@code group_type} 原文，可为 null
     * @param popedomJson {@code group_popedom} 原文，可为 null
     */
    public static GroupPermission of(int groupId, String typeIds, String popedomJson) {
        Set<String> ids = typeIds == null ? Set.of()
                : Arrays.stream(typeIds.split(",")).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        JsonNode node = null;
        if (popedomJson != null && !popedomJson.isBlank()) {
            try {
                node = JSON.readTree(popedomJson);
            } catch (JacksonException e) {
                node = null;
            }
        }
        return new GroupPermission(groupId, ids, node);
    }

    /** 没有任何权限（组不存在）。 */
    public static GroupPermission none() {
        return NONE;
    }

    /** 组行的 group_id；组不存在时为 0。 */
    public int groupId() {
        return groupId;
    }

    public boolean allows(int typeId, String bit) {
        String key = Integer.toString(typeId);
        if (!typeIds.contains(key) || popedom == null) {
            return false;
        }
        JsonNode perType = child(popedom, key);
        return perType != null && PhpValues.notEmpty(child(perType, bit));
    }

    /** PHP json_decode(…, true) 对对象和数组都得到关联数组，两种形态都按键取值。 */
    private static JsonNode child(JsonNode node, String key) {
        if (node.isObject()) {
            return node.get(key);
        }
        if (node.isArray()) {
            try {
                return node.get(Integer.parseInt(key));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}

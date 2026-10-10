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
 */
public final class GuestPermission {

    public static final String LIST = "1";
    public static final String DETAIL = "2";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final GuestPermission NONE = new GuestPermission(Set.of(), null);

    private final Set<String> typeIds;
    private final JsonNode popedom;

    private GuestPermission(Set<String> typeIds, JsonNode popedom) {
        this.typeIds = typeIds;
        this.popedom = popedom;
    }

    /** 组不存在或字段无法解析时没有任何权限（旧代码取不到组时 strpos 判定为 false）。 */
    public static GuestPermission from(GroupPermissionRow row) {
        if (row == null) {
            return NONE;
        }
        Set<String> ids = row.typeIds() == null ? Set.of()
                : Arrays.stream(row.typeIds().split(",")).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        JsonNode node = null;
        if (row.popedomJson() != null && !row.popedomJson().isBlank()) {
            try {
                node = JSON.readTree(row.popedomJson());
            } catch (JacksonException e) {
                node = null;
            }
        }
        return new GuestPermission(ids, node);
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

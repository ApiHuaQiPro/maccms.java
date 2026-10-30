package com.maccms.backend.collect.application;

import java.util.Optional;

/**
 * Provide 资源的种类（旧 {@code admin/controller/Collect.php:156-171} 按 {@code mid} 分派；{@code comment_json} 没有后台入口）。
 * {@code name} 是名称字段（预览与入库预检用），{@code binds} 为真时数据行按分类绑定换算 {@code type_id}、有分类列表。
 */
public enum ProvideKind {
    VOD("vod", "1", "vod_name", true),
    ART("art", "2", "art_name", true),
    ACTOR("actor", "8", "actor_name", true),
    ROLE("role", "9", "role_name", false),
    WEBSITE("website", "11", "website_name", true),
    /** 旧 {@code comment_json}：后台的 {@code collect/api} 没有入口（只能在测试与以后的入库批次里用）。 */
    COMMENT("comment", "", "comment_content", false);

    private final String code;
    private final String mid;
    private final String nameField;
    private final boolean binds;

    ProvideKind(String code, String mid, String nameField, boolean binds) {
        this.code = code;
        this.mid = mid;
        this.nameField = nameField;
        this.binds = binds;
    }

    /** 旧方法名前缀与 {@code maccms.php} 的 {@code collect.<code>} 配置组。 */
    public String code() {
        return code;
    }

    public String nameField() {
        return nameField;
    }

    public boolean binds() {
        return binds;
    }

    /**
     * 旧 {@code Collect::api} 的分派：{@code $param['mid'] == ''} 或 {@code == '1'} → 视频，{@code '2'} 文章，{@code '8'} 演员，{@code '9'} 角色，
     * {@code '11'} 网址；其他值没有输出。两边都是数字串时 PHP 按数值比较（{@code '01'}、{@code '1.0'} 也是视频）。
     */
    public static Optional<ProvideKind> ofMid(String mid) {
        if (mid == null || mid.isEmpty()) {
            return Optional.of(VOD);
        }
        Double n = ProvideValues.numeric(mid);
        for (ProvideKind k : values()) {
            if (k == COMMENT) {
                continue;
            }
            if (n != null ? n == Double.parseDouble(k.mid) : k.mid.equals(mid)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    public String mid() {
        return mid;
    }
}

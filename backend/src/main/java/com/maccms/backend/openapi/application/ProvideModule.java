package com.maccms.backend.openapi.application;

import com.maccms.backend.content.application.LegacyTable;
import java.util.Optional;

/** Provide 的数据模块（api/controller/Provide.php 的 vod/art/actor/role/website 操作）。 */
public enum ProvideModule {
    VOD("vod", LegacyTable.VOD, 1),
    ART("art", LegacyTable.ART, 2),
    ACTOR("actor", LegacyTable.ACTOR, 8),
    ROLE("role", LegacyTable.ROLE, 0),
    WEBSITE("website", LegacyTable.WEBSITE, 11);

    private final String key;
    private final LegacyTable table;
    private final int classMid;

    ProvideModule(String key, LegacyTable table, int classMid) {
        this.key = key;
        this.table = table;
        this.classMid = classMid;
    }

    /** 操作名与旧配置 {@code api.<key>} 的键。 */
    public String key() {
        return key;
    }

    public LegacyTable table() {
        return table;
    }

    /** JSON 分类表 {@code class} 按哪个 type_mid 过滤（视频 JSON 不过滤，见 {@link ProvideService}；角色为空表）。 */
    int classMid() {
        return classMid;
    }

    public static Optional<ProvideModule> byKey(String key) {
        for (ProvideModule m : values()) {
            if (m.key.equals(key)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}

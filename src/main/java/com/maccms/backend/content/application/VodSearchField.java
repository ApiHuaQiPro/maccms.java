package com.maccms.backend.content.application;

/**
 * 视频搜索缓存的字段（B11，旧 Vod.php:409-457 的调用顺序）：旧版写入 mac_vod_search.search_field 的字段名，与是否按逗号拆成多个词。
 * wd 的字段是 {@code vod_name|} 加旧配置 {@code search_vod_rule}；新版的关键字匹配列固定为 vod_name、vod_en、vod_sub
 * （09 方案 D1①，即仓库的 {@code search_vod_rule = vod_en|vod_sub}），所以字段名也固定为 {@code vod_name|vod_en|vod_sub}。
 */
public enum VodSearchField {
    KEYWORD("vod_name|vod_en|vod_sub", false),
    NAME("vod_name", false),
    TAG("vod_tag", true),
    CLASS("vod_class", true),
    ACTOR("vod_actor", true),
    DIRECTOR("vod_director", true);

    private final String legacyName;
    private final boolean multiple;

    VodSearchField(String legacyName, boolean multiple) {
        this.legacyName = legacyName;
        this.multiple = multiple;
    }

    public String legacyName() {
        return legacyName;
    }

    public boolean multiple() {
        return multiple;
    }
}

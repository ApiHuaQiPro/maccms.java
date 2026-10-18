package com.maccms.backend.content.application;

/** 整行读取的列集合（B06，16 文档第 3.5、4.9、4.10 节）。 */
public enum LegacyColumnSet {
    /** 旧 {@code SELECT *}：{@link LegacyTable#fullColumns()}（不含访问密码列）。 */
    FULL,
    /** Provide 列表字段：{@link LegacyTable#listColumns()}。 */
    LIST,
    /** 联想词：{@code id, name, en, pic}（Ajax.php:109）。 */
    SUGGEST,
    /** 角色关联视频的摘要：{@code vod_id, vod_douban_id, vod_name, vod_director}（Provide.php:634-636，只用于视频）。 */
    BRIEF
}

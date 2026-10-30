package com.maccms.backend.content.application;

/** 整行读取的排序（并列时按主键同向，02 规范第 3 节）。 */
public enum LegacyRowOrder {
    /** {@code <前缀>_time desc}（Provide、ajax/data 的默认）。 */
    TIME_DESC,
    /** {@code <前缀>_time asc}（Provide vod 的 sort_direction=asc）。 */
    TIME_ASC,
    /** {@code <前缀>_id desc}（ajax/suggest）。 */
    ID_DESC
}

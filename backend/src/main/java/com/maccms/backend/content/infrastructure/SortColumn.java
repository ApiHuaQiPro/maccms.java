package com.maccms.backend.content.infrastructure;

/**
 * 文章/专题/演员/角色/网址/友链列表的排序键（契约 1.2.0）。只决定 XML {@code <choose>} 中写死的列名，各 Mapper 只为
 * 本表存在的列提供分支；某类型允许哪些键由 content.application 的 {@code ContentKind} 白名单决定（旧 listCacheData 的 by）。
 */
public enum SortColumn {
    TIME,
    TIME_ADD,
    ID,
    SCORE,
    HITS,
    HITS_DAY,
    HITS_WEEK,
    HITS_MONTH,
    UP,
    DOWN,
    LEVEL,
    REFERER,
    REFERER_DAY,
    REFERER_WEEK,
    REFERER_MONTH,
    SORT
}

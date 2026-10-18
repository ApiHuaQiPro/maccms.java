package com.maccms.backend.content.application;

import java.util.List;

/**
 * 旧协议整行读取的查询（B06，16 文档第 3.3、4.9、4.10 节），由 openapi（Provide）与 site（ajax/data、ajax/suggest）按旧参数构造。
 * 构造器自带校验（越界抛 {@link IllegalArgumentException}），调用方不能靠直接构造绕过上限。值都是解码后的原始字符串，
 * 包含匹配的转义由 {@link LegacyRowReadService} 完成。行范围始终是"已审核 + 可见分类"，不能由本类放宽。
 *
 * @param table            表
 * @param columns          列集合
 * @param order            排序
 * @param ids              主键 IN 的各项（字符串，与旧版 IN ('1','abc') 相同；≤ {@value #MAX_IDS} 项、每项 ≤ 64 字符）
 * @param typeIdEquals     {@code type_id = ?}（Provide 的 t，原样字符串，≤ 64 字符）
 * @param typeIdIn         {@code type_id IN}（Provide 的 typefilter）
 * @param typeOrParentIn   {@code type_id/type_id_1 IN}（ajax/data 的 tid）
 * @param timeAfter        {@code <前缀>_time > ?}
 * @param timeBefore       {@code <前缀>_time < ?}
 * @param nameContains     名称包含（Provide 的 wd，按字面值匹配，≤ {@value #MAX_TEXT} 码点）
 * @param nameOrEnContains 名称或英文名包含（ajax/suggest 的 wd，同上）
 * @param isEnd            {@code vod_isend}（0/1，只用于视频）
 * @param years            {@code vod_year IN}（只用于视频，≤ 200 项）
 * @param playFromContains {@code vod_play_from} 包含其中任一项（只用于视频，≤ 20 项、每项 ≤ 255 字符）
 * @param conditions       datafilter 白名单条件（≤ 16 项）
 * @param offset           偏移（0..{@value #MAX_OFFSET}）
 * @param limit            条数（1..{@value #MAX_LIMIT}）
 */
public record LegacyRowQuery(LegacyTable table, LegacyColumnSet columns, LegacyRowOrder order, List<String> ids,
                             String typeIdEquals, List<Integer> typeIdIn, List<Integer> typeOrParentIn, Long timeAfter,
                             Long timeBefore, String nameContains, String nameOrEnContains, Integer isEnd,
                             List<String> years, List<String> playFromContains, List<LegacyCondition> conditions,
                             long offset, int limit) {

    public static final int MAX_IDS = 1000;
    public static final int MAX_TEXT = 64;
    public static final int MAX_LIMIT = 1000;
    public static final long MAX_OFFSET = 1_000_000_000L;

    public LegacyRowQuery {
        if (table == null || columns == null || order == null) {
            throw new IllegalArgumentException("缺少表、列集合或排序");
        }
        if (columns == LegacyColumnSet.BRIEF && table != LegacyTable.VOD) {
            throw new IllegalArgumentException("BRIEF 只用于视频");
        }
        if (offset < 0 || offset > MAX_OFFSET || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("分页越界");
        }
        ids = list(ids, MAX_IDS);
        if (ids != null) {
            for (String id : ids) {
                text(id, 64);
            }
        }
        if (typeIdEquals != null) {
            text(typeIdEquals, 64);
        }
        typeIdIn = list(typeIdIn, 1000);
        typeOrParentIn = list(typeOrParentIn, 1000);
        if ((typeIdEquals != null || typeIdIn != null || typeOrParentIn != null) && !table.typed()) {
            throw new IllegalArgumentException("该表没有分类");
        }
        if (nameContains != null) {
            text(nameContains, MAX_TEXT);
        }
        if (nameOrEnContains != null) {
            text(nameOrEnContains, MAX_TEXT);
        }
        if (isEnd != null && isEnd != 0 && isEnd != 1) {
            throw new IllegalArgumentException("isEnd 只能是 0 或 1");
        }
        years = list(years, 200);
        playFromContains = list(playFromContains, 20);
        if (playFromContains != null) {
            for (String p : playFromContains) {
                if (p.isEmpty()) {
                    throw new IllegalArgumentException("空的播放组");
                }
                text(p, 255);
            }
        }
        if ((isEnd != null || years != null || playFromContains != null) && table != LegacyTable.VOD) {
            throw new IllegalArgumentException("只用于视频的条件");
        }
        if (years != null) {
            for (String y : years) {
                text(y, 16);
            }
        }
        conditions = list(conditions, 16);
        if (conditions != null) {
            for (LegacyCondition c : conditions) {
                if (!c.column().appliesTo(table)) {
                    throw new IllegalArgumentException("该表没有这一列");
                }
            }
        }
    }

    /** 从第 offset 行起取 limit 行、其余条件为空的查询。 */
    public static LegacyRowQuery of(LegacyTable table, LegacyColumnSet columns, LegacyRowOrder order, long offset, int limit) {
        return new LegacyRowQuery(table, columns, order, null, null, null, null, null, null, null, null, null, null, null,
                null, offset, limit);
    }

    /** 是否含包含匹配（受并发许可约束）。 */
    public boolean usesContainsMatch() {
        return nameContains != null || nameOrEnContains != null || playFromContains != null;
    }

    private static <T> List<T> list(List<T> l, int max) {
        if (l == null) {
            return null;
        }
        if (l.isEmpty() || l.size() > max) {
            throw new IllegalArgumentException("列表条件项数越界");
        }
        for (T t : l) {
            if (t == null) {
                throw new IllegalArgumentException("列表条件含 null");
            }
        }
        return List.copyOf(l);
    }

    private static void text(String s, int maxCodePoints) {
        if (s.codePointCount(0, s.length()) > maxCodePoints) {
            throw new IllegalArgumentException("文本过长");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                throw new IllegalArgumentException("含控制字符");
            }
        }
    }
}

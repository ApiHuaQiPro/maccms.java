package com.maccms.backend.content.infrastructure;

import java.util.List;

/**
 * LegacyRowMapper.xml 的绑定值（B06，16 文档第 3.6 节），由 content.application 用已校验的条件构造。只作为 {@code #{}} 参数绑定，
 * 表、列集合、排序与条件列都是枚举，在 XML 中经 {@code <choose>} 换成固定写法。字段为 {@code null} 表示该条件不参与；
 * 列表字段非 {@code null} 时至少一项。
 *
 * @param table            表
 * @param columns          列集合
 * @param order            排序
 * @param typeIds          可见分类（有 type_id 的表必填，非空）
 * @param typeIdEquals     {@code type_id = ?}（Provide 的 t，旧版按字符串比较，由 MySQL 隐式转换）
 * @param typeIdIn         {@code type_id IN}（Provide 的 typefilter）
 * @param typeOrParentIn   {@code type_id IN … OR type_id_1 IN …}（ajax/data 的 tid）
 * @param ids              主键 IN（字符串绑定，与旧版 IN ('1','abc') 的隐式转换相同）
 * @param timeAfter        {@code <前缀>_time > ?}
 * @param timeBefore       {@code <前缀>_time < ?}
 * @param namePattern      名称的 LIKE 模式（已按 ESCAPE '!' 转义并加首尾 %）
 * @param nameOrEnPattern  名称或英文名的 LIKE 模式（同上）
 * @param isEnd            {@code vod_isend = ?}（只用于视频）
 * @param years            {@code vod_year IN}（字符串）
 * @param playFromPatterns {@code vod_play_from} 的 LIKE 模式（项之间 OR）
 * @param conditions       Provide 的 datafilter 白名单条件
 * @param offset           偏移
 * @param limit            条数
 */
public record LegacyRowCriteria(Table table, Columns columns, Order order, List<Integer> typeIds, String typeIdEquals,
                                List<Integer> typeIdIn, List<Integer> typeOrParentIn, List<String> ids, Long timeAfter,
                                Long timeBefore, String namePattern, String nameOrEnPattern, Integer isEnd,
                                List<String> years, List<String> playFromPatterns, List<Condition> conditions,
                                long offset, int limit) {

    public enum Table {
        VOD, ART, TOPIC, ACTOR, ROLE, WEBSITE
    }

    public enum Columns {
        FULL, LIST, SUGGEST, BRIEF
    }

    public enum Order {
        TIME_DESC, TIME_ASC, ID_DESC
    }

    public enum Column {
        STATUS, LEVEL, LOCK, ISEND, COPYRIGHT, PLOT
    }

    public enum Op {
        EQ, NE, GT, GE, LT, LE
    }

    /** {@code <前缀>_<列> <运算符> ?}。 */
    public record Condition(Column column, Op op, long value) {
    }

    public LegacyRowCriteria {
        if (table == null || columns == null || order == null || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("缺少表、列集合或排序，或分页越界");
        }
        typeIds = copy(typeIds);
        typeIdIn = copy(typeIdIn);
        typeOrParentIn = copy(typeOrParentIn);
        ids = copy(ids);
        years = copy(years);
        playFromPatterns = copy(playFromPatterns);
        conditions = copy(conditions);
    }

    private static <T> List<T> copy(List<T> list) {
        if (list == null) {
            return null;
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("列表条件至少一项");
        }
        return List.copyOf(list);
    }
}

package com.maccms.backend.content.infrastructure;

/** 分类的编号、名称、模型与上级（ContentLookupMapper.xml）。 */
public record TypeNodeRow(Integer id, String name, Integer mid, Integer parentId) {
}

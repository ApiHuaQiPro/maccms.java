package com.maccms.backend.content.infrastructure;

/**
 * 按编号查到的内容名称、图片与分类（ContentLookupMapper.xml）。专题、演员没有分类（typeFound=0）；分类已不存在时 typeFound=0。
 */
public record ContentTitleRow(Long id, String name, String pic, Integer typeId, String typeName, Integer typeFound) {
}

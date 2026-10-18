package com.maccms.backend.content.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读（B06）。SQL 见 resources/mapper/content/LegacyRowMapper.xml。结果是"列名 → 值"（整数列为 Long，DECIMAL 与文本列为 String），
 * 不保证顺序，由 content.application 按旧表的列顺序重排。
 */
@Mapper
public interface LegacyRowMapper {

    long count(@Param("q") LegacyRowCriteria q);

    List<Map<String, Object>> select(@Param("q") LegacyRowCriteria q);

    /** 全部分类（不论状态与模型），按 type_id 升序。 */
    List<Map<String, Object>> selectTypes();
}

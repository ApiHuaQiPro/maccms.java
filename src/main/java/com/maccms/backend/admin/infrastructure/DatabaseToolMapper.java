package com.maccms.backend.admin.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 数据库工具的只读查询（B15，24 文档第 9 节）。SQL 见 {@code resources/mapper/admin/DatabaseToolMapper.xml}；{@code table}、{@code columns}
 * 由调用方按 {@code SHOW TABLE STATUS}/{@code SHOW COLUMNS} 校验后才传入（{@code SqlEntryGuardTest.REGISTERED_DOLLAR}）。
 */
@Mapper
public interface DatabaseToolMapper {

    List<LinkedHashMap<String, Object>> tableStatus();

    List<LinkedHashMap<String, Object>> columns(@Param("table") String table);

    LinkedHashMap<String, Object> createTable(@Param("table") String table);

    long count(@Param("table") String table);

    List<LinkedHashMap<String, Object>> rows(@Param("table") String table, @Param("columns") String columns, @Param("offset") long offset);
}

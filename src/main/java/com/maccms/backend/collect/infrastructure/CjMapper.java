package com.maccms.backend.collect.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 自定义规则采集的只读查询（B20，docs/architecture/28-b20-cj-local-html.md 第 5 节；SQL 见 {@code resources/mapper/collect/CjMapper.xml}）。
 * 行按文字取回。
 */
@Mapper
public interface CjMapper {

    long countHistory(@Param("md5") String md5);

    /** {@code status} 为 null 时不按状态筛选。 */
    long countContents(@Param("nodeid") long nodeid, @Param("status") String status);

    List<Map<String, String>> listContents(@Param("nodeid") long nodeid, @Param("status") String status, @Param("offset") long offset,
                                           @Param("limit") long limit);

    Map<String, String> selectContent(@Param("id") long id);

    long countPending(@Param("nodeid") long nodeid);

    List<Map<String, String>> listPending(@Param("nodeid") long nodeid, @Param("limit") int limit);

    /** {@code ids} 为 null 时不限编号（旧 {@code all=1}）。 */
    long countImportable(@Param("nodeid") long nodeid, @Param("ids") List<Long> ids);

    List<Map<String, String>> listImportable(@Param("nodeid") long nodeid, @Param("ids") List<Long> ids, @Param("after") long after,
                                             @Param("limit") int limit);

    /** {@code ids} 为 null 时是全部内容（旧 {@code all=1}）。 */
    List<Map<String, String>> listContentUrls(@Param("ids") List<Long> ids, @Param("after") long after, @Param("limit") int limit);
}

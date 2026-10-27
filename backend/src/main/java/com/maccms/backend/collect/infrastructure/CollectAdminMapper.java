package com.maccms.backend.collect.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 采集配置管理的只读查询（B17，docs/architecture/25-b17-collect-config.md 第 5 节；SQL 见
 * {@code resources/mapper/collect/CollectAdminMapper.xml}）。行按文字取回（整数为十进制，与旧模板输出的文字相同）。采集源不读
 * {@code collect_appid}、{@code collect_appkey}（{@code ReadOnlyGuardrailsTest} 锁定）。
 */
@Mapper
public interface CollectAdminMapper {

    // ---------------------------------------------------------------- mac_collect

    Map<String, String> selectCollect(@Param("id") long id);

    long countCollects();

    List<Map<String, String>> listCollects(@Param("offset") long offset, @Param("limit") long limit);

    // ---------------------------------------------------------------- mac_cj_node

    Map<String, String> selectCjNode(@Param("id") long id);

    long countCjNodes();

    List<Map<String, String>> listCjNodes(@Param("offset") long offset, @Param("limit") long limit);

    /** 节点下的内容（编号大于 {@code after}，按编号升序，最多 {@code limit} 行）的编号与网址。 */
    List<Map<String, String>> listCjContentUrls(@Param("ids") List<Long> ids, @Param("after") long after, @Param("limit") int limit);
}

package com.maccms.backend.collect.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 采集配置管理的写 SQL（B17，25 文档第 6 节；WriteRegistry 登记 collect 的 mac_collect、mac_cj_node 增删改与 mac_cj_content、
 * mac_cj_history 删除）。SQL 见 {@code resources/mapper-write/collect/CollectAdminWriteMapper.xml}：新增与按主键整行覆盖只写参数里有的列，
 * 列名写死在 XML 里；只能经 {@code WriteDatabase.mapper()} 取得。
 */
@WriteMapper
public interface CollectAdminWriteMapper {

    int insertCollect(CollectInsert row);

    int updateCollect(@Param("c") Map<String, String> c, @Param("id") long id);

    int deleteCollects(@Param("ids") List<Long> ids);

    int insertCjNode(CollectInsert row);

    int updateCjNode(@Param("c") Map<String, String> c, @Param("id") long id);

    int deleteCjNodes(@Param("ids") List<Long> ids);

    int deleteCjHistories(@Param("ids") List<String> md5s);

    int deleteCjContentsOfNodes(@Param("ids") List<Long> ids);
}

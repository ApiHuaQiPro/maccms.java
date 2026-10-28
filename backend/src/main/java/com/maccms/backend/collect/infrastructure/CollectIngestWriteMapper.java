package com.maccms.backend.collect.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 采集入库的写 SQL（B19，27 文档第 4 节；WriteRegistry 登记 collect 对 mac_vod、mac_art、mac_actor、mac_role、mac_website、mac_comment 的
 * 新增与修改）。SQL 见
 * {@code resources/mapper-write/collect/CollectIngestWriteMapper.xml}：只写参数里有的列，列名写死在 XML 里；只能经
 * {@code WriteDatabase.mapper()} 取得。
 */
@WriteMapper
public interface CollectIngestWriteMapper {

    int insertVod(CollectInsert row);

    int updateVod(@Param("c") Map<String, String> c, @Param("id") long id);

    int insertArt(CollectInsert row);

    int updateArt(@Param("c") Map<String, String> c, @Param("id") long id);

    int insertActor(CollectInsert row);

    int updateActor(@Param("c") Map<String, String> c, @Param("id") long id);

    int insertRole(CollectInsert row);

    int updateRole(@Param("c") Map<String, String> c, @Param("id") long id);

    int insertWebsite(CollectInsert row);

    int updateWebsite(@Param("c") Map<String, String> c, @Param("id") long id);

    int insertComment(CollectInsert row);

    int updateComment(@Param("c") Map<String, String> c, @Param("id") long id);
}

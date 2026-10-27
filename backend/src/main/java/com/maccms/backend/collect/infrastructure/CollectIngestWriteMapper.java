package com.maccms.backend.collect.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 采集入库的写 SQL（B19，27 文档第 4 节；WriteRegistry 登记 collect 的 mac_vod 新增与修改）。SQL 见
 * {@code resources/mapper-write/collect/CollectIngestWriteMapper.xml}：只写参数里有的列，列名写死在 XML 里；只能经
 * {@code WriteDatabase.mapper()} 取得。
 */
@WriteMapper
public interface CollectIngestWriteMapper {

    int insertVod(CollectInsert row);

    int updateVod(@Param("c") Map<String, String> c, @Param("id") long id);
}

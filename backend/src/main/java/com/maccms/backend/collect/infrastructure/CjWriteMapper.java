package com.maccms.backend.collect.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 自定义规则采集的写 SQL（B20，28 文档第 5 节；SQL 见 {@code resources/mapper-write/collect/CjWriteMapper.xml}）；只能经
 * {@code WriteDatabase.mapper()} 取得。
 */
@WriteMapper
public interface CjWriteMapper {

    int insertHistory(@Param("md5") String md5);

    int insertContent(@Param("nodeid") long nodeid, @Param("url") String url, @Param("title") String title);

    int updateCollected(@Param("id") long id, @Param("nodeid") long nodeid, @Param("data") String data);

    int updatePublished(@Param("ids") List<Long> ids);

    int deleteContents(@Param("ids") List<Long> ids);

    int deleteAllContents();
}

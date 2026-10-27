package com.maccms.backend.collect.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 采集入库的查重（B19，27 文档第 4 节；SQL 见 {@code resources/mapper/collect/CollectIngestMapper.xml}）。行按文字取回。
 */
@Mapper
public interface CollectIngestMapper {

    /**
     * 旧 {@code model('Vod')->where($where)->find()}：不排序取第一行。参数为 null 的条件不参与。
     *
     * @param actorLikes {@code vod_actor LIKE} 的模式，任一匹配（不转义通配符，同旧版）
     * @param blend      为真时主演与导演组成 {@code (vod_director = ? OR 主演条件)}；主演条件在 {@code blendIds} 非空时为
     *                   {@code vod_id IN (...)}
     */
    Map<String, String> findVod(@Param("name") String name, @Param("typeId") String typeId, @Param("year") String year,
                                @Param("area") String area, @Param("lang") String lang, @Param("actorLikes") List<String> actorLikes,
                                @Param("director") String director, @Param("blend") boolean blend,
                                @Param("blendIds") List<Long> blendIds);
}

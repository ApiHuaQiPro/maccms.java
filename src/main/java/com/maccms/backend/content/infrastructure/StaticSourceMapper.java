package com.maccms.backend.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。静态生成的数据来源（B16），SQL 见 resources/mapper/content/StaticSourceMapper.xml。 */
@Mapper
public interface StaticSourceMapper {

    /** 内容页的筛选条件（{@code typeId} 优先于 {@code ids}，同旧 Make::info 的 if/elseif）。 */
    record Filter(Long typeId, List<Long> ids, Long since, boolean notMade) {
    }

    List<StaticTypeRow> selectTypes();

    long countVodInType(@Param("typeId") long typeId);

    long countArtInType(@Param("typeId") long typeId);

    long countVod(@Param("f") Filter f);

    long countArt(@Param("f") Filter f);

    List<StaticItemRow> selectVod(@Param("f") Filter f, @Param("offset") long offset, @Param("limit") int limit);

    List<StaticItemRow> selectArt(@Param("f") Filter f, @Param("offset") long offset, @Param("limit") int limit);

    List<Long> selectVodTypesSince(@Param("since") long since);

    List<Long> selectArtTypesSince(@Param("since") long since);

    List<StaticTopicRow> selectTopics();

    StaticTopicRow selectTopic(@Param("id") long id);

    long countTopics();
}

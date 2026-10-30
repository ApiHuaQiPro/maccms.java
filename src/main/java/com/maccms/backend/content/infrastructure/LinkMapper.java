package com.maccms.backend.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。SQL 见 resources/mapper/content/LinkMapper.xml。排序只接受 {@link SortColumn#ID}、{@link SortColumn#SORT}。 */
@Mapper
public interface LinkMapper {

    long count(@Param("criteria") ContentCriteria criteria);

    List<LinkRow> selectPage(@Param("criteria") ContentCriteria criteria,
                             @Param("sort") SortColumn sort,
                             @Param("descending") boolean descending,
                             @Param("offset") long offset,
                             @Param("limit") int limit);
}

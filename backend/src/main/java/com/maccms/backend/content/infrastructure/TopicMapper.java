package com.maccms.backend.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。SQL 见 resources/mapper/content/TopicMapper.xml。专题没有分类；排序只接受 {@link SortColumn}。 */
@Mapper
public interface TopicMapper {

    long countPublished(@Param("criteria") ContentCriteria criteria);

    List<TopicSummaryRow> selectPublishedPage(@Param("criteria") ContentCriteria criteria,
                                              @Param("sort") SortColumn sort,
                                              @Param("descending") boolean descending,
                                              @Param("offset") long offset,
                                              @Param("limit") int limit);

    TopicDetailRow selectPublishedDetail(@Param("id") long id);
}

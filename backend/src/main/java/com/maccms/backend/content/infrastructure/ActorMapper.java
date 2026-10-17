package com.maccms.backend.content.infrastructure;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。SQL 见 resources/mapper/content/ActorMapper.xml。调用方必须保证 typeIds 非空；排序只接受 {@link SortColumn}。 */
@Mapper
public interface ActorMapper {

    long countPublished(@Param("typeIds") Collection<Integer> typeIds, @Param("criteria") ContentCriteria criteria);

    List<ActorSummaryRow> selectPublishedPage(@Param("typeIds") Collection<Integer> typeIds,
                                              @Param("criteria") ContentCriteria criteria,
                                              @Param("sort") SortColumn sort,
                                              @Param("descending") boolean descending,
                                              @Param("offset") long offset,
                                              @Param("limit") int limit);

    ActorDetailRow selectPublishedDetail(@Param("id") long id);
}

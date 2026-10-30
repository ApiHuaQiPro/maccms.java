package com.maccms.backend.content.infrastructure;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。SQL 见 resources/mapper/content/WebsiteMapper.xml。调用方必须保证 typeIds 非空；排序只接受 {@link SortColumn}。 */
@Mapper
public interface WebsiteMapper {

    long countPublished(@Param("typeIds") Collection<Integer> typeIds, @Param("criteria") ContentCriteria criteria);

    List<WebsiteSummaryRow> selectPublishedPage(@Param("typeIds") Collection<Integer> typeIds,
                                                @Param("criteria") ContentCriteria criteria,
                                                @Param("sort") SortColumn sort,
                                                @Param("descending") boolean descending,
                                                @Param("offset") long offset,
                                                @Param("limit") int limit);

    WebsiteDetailRow selectPublishedDetail(@Param("id") long id);
}

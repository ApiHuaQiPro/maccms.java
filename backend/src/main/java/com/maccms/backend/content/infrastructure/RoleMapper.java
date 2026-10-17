package com.maccms.backend.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。SQL 见 resources/mapper/content/RoleMapper.xml。角色没有分类；排序只接受 {@link SortColumn}。 */
@Mapper
public interface RoleMapper {

    long countPublished(@Param("criteria") ContentCriteria criteria);

    List<RoleSummaryRow> selectPublishedPage(@Param("criteria") ContentCriteria criteria,
                                             @Param("sort") SortColumn sort,
                                             @Param("descending") boolean descending,
                                             @Param("offset") long offset,
                                             @Param("limit") int limit);

    RoleDetailRow selectPublishedDetail(@Param("id") long id);
}

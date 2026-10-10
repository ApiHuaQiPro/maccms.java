package com.maccms.backend.content.infrastructure;

import com.maccms.backend.content.application.VideoSort;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读。SQL 见 resources/mapper/content/VodMapper.xml。
 * 调用方必须保证 typeIds 非空；排序只接受 VideoSort 枚举，XML 内用 choose 生成固定列名。
 */
@Mapper
public interface VodMapper {

    long countPublished(@Param("typeIds") Collection<Integer> typeIds);

    List<VodSummaryRow> selectPublishedPage(@Param("typeIds") Collection<Integer> typeIds,
                                            @Param("sort") VideoSort sort,
                                            @Param("descending") boolean descending,
                                            @Param("offset") long offset,
                                            @Param("limit") int limit);

    VodDetailRow selectPublishedDetail(@Param("id") long id);
}

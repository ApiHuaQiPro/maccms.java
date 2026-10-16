package com.maccms.backend.content.infrastructure;

import com.maccms.backend.content.application.VideoSort;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读。SQL 见 resources/mapper/content/VodMapper.xml。
 * 调用方必须保证 typeIds 非空；排序只接受 VideoSort 枚举，XML 内用 choose 生成固定列名。
 *
 * <p>不带搜索/筛选条件时使用 {@link #countPublished}/{@link #selectPublishedPage}（与契约 1.0.0 相同的 SQL）；
 * 带条件时使用 {@code *Matching} 两条语句，它们与前两条共用可见性与排序片段，另加同一个条件片段和服务端执行时限。
 */
@Mapper
public interface VodMapper {

    long countPublished(@Param("typeIds") Collection<Integer> typeIds);

    List<VodSummaryRow> selectPublishedPage(@Param("typeIds") Collection<Integer> typeIds,
                                            @Param("sort") VideoSort sort,
                                            @Param("descending") boolean descending,
                                            @Param("offset") long offset,
                                            @Param("limit") int limit);

    long countPublishedMatching(@Param("typeIds") Collection<Integer> typeIds,
                                @Param("filter") VodFilterCriteria filter);

    List<VodSummaryRow> selectPublishedMatchingPage(@Param("typeIds") Collection<Integer> typeIds,
                                                    @Param("filter") VodFilterCriteria filter,
                                                    @Param("sort") VideoSort sort,
                                                    @Param("descending") boolean descending,
                                                    @Param("offset") long offset,
                                                    @Param("limit") int limit);

    VodDetailRow selectPublishedDetail(@Param("id") long id);
}

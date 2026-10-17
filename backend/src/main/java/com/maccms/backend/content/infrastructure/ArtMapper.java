package com.maccms.backend.content.infrastructure;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读。SQL 见 resources/mapper/content/ArtMapper.xml。调用方必须保证 typeIds（以及 ids、patterns）非空；
 * 排序只接受 {@link SortColumn}，XML 内用 choose 生成固定列名。
 */
@Mapper
public interface ArtMapper {

    long countPublished(@Param("typeIds") Collection<Integer> typeIds, @Param("criteria") ContentCriteria criteria);

    List<ArtSummaryRow> selectPublishedPage(@Param("typeIds") Collection<Integer> typeIds,
                                            @Param("criteria") ContentCriteria criteria,
                                            @Param("sort") SortColumn sort,
                                            @Param("descending") boolean descending,
                                            @Param("offset") long offset,
                                            @Param("limit") int limit);

    ArtDetailRow selectPublishedDetail(@Param("id") long id);

    /** 访问密码凭据（B05）：已审核文章的 art_pwd 非空且 SHA2(CONCAT(salt, art_pwd), 256) = hash 时为 1，否则为 0。 */
    int countPasswordProven(@Param("id") long id, @Param("proofSalt") String proofSalt,
                            @Param("proofHash") String proofHash);

    /** 访问密码校验（B05，旧 ajax/pwd 的 2-1）：已审核文章的 art_pwd 非空且与 candidate 逐字节相等时为 1，否则为 0。 */
    int countPasswordMatch(@Param("id") long id, @Param("candidate") String candidate);

    /** 专题关联文章：art_id 在 ids 中，按 art_time 倒序，最多 999 条（旧 Topic::infoData）。 */
    List<ArtSummaryRow> selectPublishedByIds(@Param("typeIds") Collection<Integer> typeIds,
                                             @Param("ids") Collection<Long> ids);

    /** 专题按标签关联文章：art_tag 包含任一模式（LIKE ... ESCAPE '!'），按 art_time 倒序，最多 999 条。 */
    List<ArtSummaryRow> selectPublishedByTagPatterns(@Param("typeIds") Collection<Integer> typeIds,
                                                     @Param("patterns") Collection<String> patterns);
}

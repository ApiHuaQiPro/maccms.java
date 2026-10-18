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

    /**
     * 随机排序（契约 1.3.0 sort=rnd）的 ID 段：全表 MIN/MAX(vod_id)，不带任何条件（与旧 Vod.php:509 相同——ThinkPHP 每次查询后
     * 清空条件，thinkphp/library/think/db/Query.php:3013，所以旧版这条语句不含前一次 count 的 where）。
     */
    VodIdRange selectIdRange();

    VodDetailRow selectPublishedDetail(@Param("id") long id);

    /** 专题关联视频（MACC-27）：vod_id 在 ids 中，按 vod_time 倒序，最多 999 条（旧 Topic::infoData）。ids 非空。 */
    List<VodSummaryRow> selectPublishedByIds(@Param("typeIds") Collection<Integer> typeIds,
                                             @Param("ids") Collection<Long> ids);

    /** 专题按标签关联视频（MACC-27）：vod_tag 包含任一模式（LIKE ... ESCAPE '!'），按 vod_time 倒序，最多 999 条。patterns 非空。 */
    List<VodSummaryRow> selectPublishedByTagPatterns(@Param("typeIds") Collection<Integer> typeIds,
                                                     @Param("patterns") Collection<String> patterns);

    /** 角色所属视频的摘要（MACC-27）：已审核的视频，不按分类过滤（由调用方判定可见性）。ids 非空。 */
    List<VodRefRow> selectPublishedRefs(@Param("ids") Collection<Long> ids);

    /**
     * 播放/下载入口（B05）：已审核视频的播放（down=false）或下载（down=true）分组与授权列。proofSalt/proofHash 同时非空时
     * 另算 {@code SHA2(CONCAT(salt, 密码列), 256) = hash}，否则 passwordProven 为 0。
     */
    VodPlaybackRow selectPlayback(@Param("id") long id, @Param("down") boolean down,
                                  @Param("proofSalt") String proofSalt, @Param("proofHash") String proofHash);

    /**
     * 访问密码校验（B05）：已审核视频的播放（down=false，vod_pwd_play）或下载（down=true，vod_pwd_down）密码列非空且与
     * candidate 逐字节相等（BINARY）时为 1，否则为 0。列名由 XML 的 choose 固定写出。
     */
    int countPasswordMatch(@Param("id") long id, @Param("down") boolean down, @Param("candidate") String candidate);
}

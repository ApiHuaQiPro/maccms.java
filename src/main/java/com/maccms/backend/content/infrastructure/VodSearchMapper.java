package com.maccms.backend.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 视频搜索缓存 mac_vod_search 的只读查询与"未命中时"的结果扫描（B11，旧 {@code VodSearch::getResultIdList}，
 * application/common/model/VodSearch.php:18-64）。SQL 见 {@code mapper/content/VodSearchMapper.xml}。
 */
@Mapper
public interface VodSearchMapper {

    VodSearchRow selectByKey(@Param("key") String key);

    /**
     * 旧版未命中时的扫描：{@code mac_vod} 中字段包含该词的全部 vod_id（不看审核状态与分类），按 vod_id 升序。新版最多取 {@code limit} 个
     * （调用方传 1001：超过 1000 个时旧版也不用这份列表，20 文档 D-B11-9）。
     *
     * @param field   VodSearchField 的名称（KEYWORD、NAME、TAG、CLASS、ACTOR、DIRECTOR），XML 中按固定列分支
     * @param pattern 已按 ESCAPE '!' 转写的 LIKE 模式（与旧版未转义的模式匹配同样的行）
     */
    List<Long> selectMatchingIds(@Param("field") String field, @Param("pattern") String pattern,
                                 @Param("limit") int limit);

    /**
     * 旧 {@code checkAndUpdateTopResults} 的候选行（VodSearch.php:107）：命中次数最多的前 {@code limit} 行（键、词、字段），命中次数相同时
     * 最近命中的在前，再按键使顺序确定。
     */
    List<java.util.Map<String, String>> selectTopRows(@Param("limit") int limit);
}

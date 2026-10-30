package com.maccms.backend.content.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 视频搜索缓存的写入（B11，20 文档第 6 节；登记 WriteRegistry：content / mac_vod_search INSERT、UPDATE；B12 后台清理缓存的 DELETE）。SQL 见
 * {@code resources/mapper-write/content/VodSearchWriteMapper.xml}。mac_vod_search 是 InnoDB 表，主键 search_key：同一个词并发首次搜索时
 * 只有一个 INSERT 成功，其余得到重复键错误，调用方改为按命中处理。
 */
@WriteMapper
public interface VodSearchWriteMapper {

    int insertResult(@Param("key") String key, @Param("word") String word, @Param("field") String field,
                     @Param("now") long now, @Param("count") int count, @Param("ids") String ids);

    int touchResult(@Param("key") String key, @Param("now") long now);

    /** 后台清理缓存（B12，登记 content / mac_vod_search DELETE）：删除结果更新时间早于 {@code before} 的全部行。 */
    int deleteUpdatedBefore(@Param("before") long before);

    /**
     * 后台新增视频后把它追加进一条已有的搜索结果（B13；旧 VodSearch::checkAndUpdateTopResults，VodSearch.php:121-125）：结果数 +1、编号以逗号
     * 接在末尾、刷新结果更新时间（登记 content / mac_vod_search UPDATE，B11 已有）。
     */
    int appendResult(@Param("key") String key, @Param("id") long id, @Param("now") long now);
}

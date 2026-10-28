package com.maccms.backend.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。后台 URL 推送的数据来源（B28），SQL 见 resources/mapper/content/UrlsendSourceMapper.xml。 */
@Mapper
public interface UrlsendSourceMapper {

    /**
     * @param mid   模型：1 视频、2 文章、3 专题、8 演员、9 角色、11 网址（其他值没有数据）
     * @param since 只取时间晚于它的行（"当天"），null 不限
     * @param added 按新增时间（{@code *_time_add}）而不是更新时间（{@code *_time}）比较
     * @param ids   只取这些编号，null 不限
     */
    record Query(int mid, Long since, boolean added, List<Long> ids) {
    }

    long count(@Param("q") Query q);

    List<UrlsendItemRow> select(@Param("q") Query q, @Param("offset") long offset, @Param("limit") int limit);
}

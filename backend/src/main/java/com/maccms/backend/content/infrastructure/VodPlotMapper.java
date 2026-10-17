package com.maccms.backend.content.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读。SQL 见 resources/mapper/content/VodPlotMapper.xml。与 VodMapper 分开：VodMapper.xml 禁止出现 vod_plot*，
 * 这里是唯一读取分集剧情的语句。
 */
@Mapper
public interface VodPlotMapper {

    VodPlotRow selectPublishedPlot(@Param("id") long id);
}

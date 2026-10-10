package com.maccms.backend.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 只读。SQL 见 resources/mapper/content/TypeMapper.xml。 */
@Mapper
public interface TypeMapper {

    /** 启用中的视频分类（type_mid=1 且 type_status=1），按 type_sort、type_id 升序。 */
    List<TypeRow> selectEnabledVodTypes();
}

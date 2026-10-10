package com.maccms.backend.content.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。SQL 见 resources/mapper/content/GroupMapper.xml。 */
@Mapper
public interface GroupMapper {

    GroupPermissionRow selectGroupPermission(@Param("groupId") int groupId);
}

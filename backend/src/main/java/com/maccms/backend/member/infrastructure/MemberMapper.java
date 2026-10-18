package com.maccms.backend.member.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读。SQL 见 resources/mapper/member/MemberMapper.xml。B08 不写 {@code mac_user}/{@code mac_group}：
 * 账号写入归 B09（12 文档第 3.1 节写入方登记），到期降级的批量任务归 B10。
 */
@Mapper
public interface MemberMapper {

    /**
     * 旧 {@code checkLogin} 的查询（{@code common/model/User.php:436-441}）：{@code user_id}、{@code user_name} 都按字符串比较，
     * 比较规则交给 MySQL（整数列与字符串比较时按数值、{@code user_name} 按列的排序规则不分大小写并忽略尾部空格），与旧版绑定字符串参数相同。
     */
    MemberLoginRow selectActiveUser(@Param("userId") String userId, @Param("userName") String userName);

    MemberGroupRow selectGroup(@Param("groupId") int groupId);
}

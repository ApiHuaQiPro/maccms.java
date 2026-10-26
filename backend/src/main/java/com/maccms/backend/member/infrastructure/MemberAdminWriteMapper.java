package com.maccms.backend.member.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 后台会员与财务管理的写入（B14，docs/architecture/23-b14-admin-member.md 第 6 节；WriteRegistry 登记 member 对 mac_user、mac_group、
 * mac_card、mac_order、mac_cash、mac_plog、mac_ulog、mac_visit 的写入）。SQL 见
 * {@code resources/mapper-write/member/MemberAdminWriteMapper.xml}；只能经 {@code WriteDatabase.mapper()} 取得。每个方法返回<b>匹配</b>行数。
 * 到期降级、撤销提现的认领与补偿、退回冻结积分沿用 B10 的 {@link MemberFinanceWriteMapper}。
 */
@WriteMapper
public interface MemberAdminWriteMapper {

    // ---------------------------------------------------------------- mac_user

    int insertUser(MemberAdminInsert row);

    int updateUser(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateUserByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteUsers(@Param("ids") List<Long> ids);

    // ---------------------------------------------------------------- mac_group

    int insertGroup(MemberAdminInsert row);

    int updateGroup(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateGroupByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    /** 删除指定的会员组，组下还有会员的不删（删除前的检查与删除之间被分配了会员时）。 */
    int deleteEmptyGroups(@Param("ids") List<Long> ids);

    // ---------------------------------------------------------------- mac_card

    int insertCards(@Param("rows") List<CardInsert> rows, @Param("money") long money, @Param("points") long points,
                    @Param("now") long now);

    int deleteCards(@Param("ids") List<Long> ids);

    /** 旧"清空"（{@code card_id > 0}）。 */
    int deleteAllCards();

    // ---------------------------------------------------------------- mac_order

    int deleteOrders(@Param("ids") List<Long> ids);

    int deleteAllOrders();

    // ---------------------------------------------------------------- mac_cash

    /** 审核的认领：未审核才命中。 */
    int auditCash(@Param("cashId") long cashId, @Param("now") long now);

    /** 审核的补偿：仍是本次写入的审核状态与时间才命中，恢复审核前的时间。 */
    int revertAudit(@Param("cashId") long cashId, @Param("now") long now, @Param("oldAudit") long oldAudit);

    /** 审核通过后扣减冻结积分：冻结积分足够才命中。 */
    int settleFrozen(@Param("userId") long userId, @Param("points") long points);

    // ---------------------------------------------------------------- mac_plog / mac_ulog / mac_visit

    int deletePlogs(@Param("ids") List<Long> ids);

    int deleteAllPlogs();

    int deleteUlogs(@Param("ids") List<Long> ids);

    int deleteAllUlogs();

    int deleteVisits(@Param("ids") List<Long> ids);

    int deleteAllVisits();
}

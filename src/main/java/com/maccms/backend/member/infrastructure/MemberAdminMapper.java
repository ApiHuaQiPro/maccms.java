package com.maccms.backend.member.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 后台会员与财务管理的只读查询（B14，docs/architecture/23-b14-admin-member.md 第 5 节；SQL 见
 * {@code resources/mapper/member/MemberAdminMapper.xml}）。列表行按文字取回（整数为十进制、decimal 保留小数位，与旧模板输出的文字相同），
 * 只供后台的会员服务使用。会员行不读密码、登录随机串与第三方登录标识（{@code ReadOnlyGuardrailsTest} 锁定）。
 */
@Mapper
public interface MemberAdminMapper {

    /** 一次按编号查找的上限（列表一页最多 1000 行）。 */
    int MAX_IDS = 1000;

    // ---------------------------------------------------------------- mac_user

    Map<String, String> selectUser(@Param("id") long id);

    long countUsers(@Param("c") MemberAdminCriteria c);

    List<Map<String, String>> listUsers(@Param("c") MemberAdminCriteria c, @Param("offset") long offset,
                                        @Param("limit") long limit);

    /** 推广人的 1/2/3 级推广会员数（{@code user_pid}、{@code user_pid_2}、{@code user_pid_3} 等于推广人）。 */
    long countInvitees(@Param("level") int level, @Param("uid") long uid);

    /** 会员是否存在（审核、撤销提现时区分"会员已删除"与"冻结积分不够"）。 */
    long countUser(@Param("id") long id);

    /** 指定会员组下的会员数（删除会员组前的检查）。 */
    long countUsersInGroups(@Param("ids") Collection<Long> ids);

    // ---------------------------------------------------------------- mac_group

    List<Map<String, String>> listGroups(@Param("c") MemberAdminCriteria c);

    Map<String, String> selectGroup(@Param("id") long id);

    /** 指定编号中还存在的会员组数。 */
    long countGroups(@Param("ids") Collection<Long> ids);

    // ---------------------------------------------------------------- mac_card

    long countCards(@Param("c") MemberAdminCriteria c);

    List<Map<String, String>> listCards(@Param("c") MemberAdminCriteria c, @Param("offset") long offset,
                                        @Param("limit") long limit);

    /** 旧 {@code model('Card')->max('card_add_time')}：没有卡时为 null。 */
    Long maxCardAddTime();

    // ---------------------------------------------------------------- mac_order

    long countOrders(@Param("c") MemberAdminCriteria c);

    List<Map<String, String>> listOrders(@Param("c") MemberAdminCriteria c, @Param("offset") long offset,
                                         @Param("limit") long limit);

    // ---------------------------------------------------------------- mac_cash

    long countCashes(@Param("c") MemberAdminCriteria c);

    List<Map<String, String>> listCashes(@Param("c") MemberAdminCriteria c, @Param("offset") long offset,
                                         @Param("limit") long limit);

    /**
     * 审核、删除要处理的提现行（按编号升序）。
     *
     * @param ids 为 null 时取 {@code cash_id > after} 的前 {@code limit} 行（旧"清空"按批处理）
     */
    List<CashRow> selectCashes(@Param("ids") Collection<Long> ids, @Param("after") long after, @Param("limit") int limit);

    // ---------------------------------------------------------------- mac_plog / mac_ulog / mac_visit

    long countPlogs(@Param("c") MemberAdminCriteria c);

    List<Map<String, String>> listPlogs(@Param("c") MemberAdminCriteria c, @Param("offset") long offset,
                                        @Param("limit") long limit);

    /** 推广人某一级的分销奖励积分合计（旧 {@code Db::name('Plog')->sum('plog_points')}）。 */
    long sumPlogPoints(@Param("uid") long uid, @Param("type") int type);

    long countUlogs(@Param("c") MemberAdminCriteria c);

    List<Map<String, String>> listUlogs(@Param("c") MemberAdminCriteria c, @Param("offset") long offset,
                                        @Param("limit") long limit);

    long countVisits(@Param("c") MemberAdminCriteria c);

    List<Map<String, String>> listVisits(@Param("c") MemberAdminCriteria c, @Param("offset") long offset,
                                         @Param("limit") long limit);
}

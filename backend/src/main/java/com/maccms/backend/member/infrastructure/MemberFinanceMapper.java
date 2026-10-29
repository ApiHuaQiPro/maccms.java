package com.maccms.backend.member.infrastructure;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会员权益与财务的只读查询（B10，docs/architecture/19-b10-member-finance.md）。SQL 见
 * {@code resources/mapper/member/MemberFinanceMapper.xml}。列表都有 LIMIT（页大小上限 100），同一组条件的 count 与列表用同一个片段。
 * 条件里的 {@code 0} 表示"不加这个条件"。
 */
@Mapper
public interface MemberFinanceMapper {

    WalletRow selectWallet(@Param("userId") long userId);

    /** 七项都相等的第一条记录（旧 {@code Ulog::infoData}）。 */
    UlogRow selectUlogMatch(@Param("userId") long userId, @Param("mid") int mid, @Param("type") int type,
                            @Param("rid") long rid, @Param("sid") int sid, @Param("nid") int nid,
                            @Param("points") int points);

    long countUlogs(@Param("userId") long userId, @Param("mid") int mid, @Param("rid") long rid,
                    @Param("type") int type);

    List<UlogRow> selectUlogs(@Param("userId") long userId, @Param("mid") int mid, @Param("rid") long rid,
                              @Param("type") int type, @Param("offset") long offset, @Param("limit") int limit);

    long countPlogs(@Param("userId") long userId);

    List<PlogRow> selectPlogs(@Param("userId") long userId, @Param("offset") long offset, @Param("limit") int limit);

    long countCashes(@Param("userId") long userId);

    List<CashRow> selectCashes(@Param("userId") long userId, @Param("offset") long offset, @Param("limit") int limit);

    /**
     * 要撤销的提现：{@code ids} 为 null 时是该会员 cash_id 大于 {@code after} 的全部（按 cash_id 分批，每批最多 {@code limit} 行）。
     */
    List<CashRow> selectCashesFor(@Param("userId") long userId, @Param("ids") Collection<Long> ids,
                                  @Param("after") long after, @Param("limit") int limit);

    long countOrders(@Param("userId") long userId);

    List<OrderRow> selectOrders(@Param("userId") long userId, @Param("offset") long offset, @Param("limit") int limit);

    OrderRow selectOrderByCode(@Param("userId") long userId, @Param("code") String code);

    OrderRow selectOrderById(@Param("userId") long userId, @Param("orderId") long orderId);

    OrderRow selectOrderForPay(@Param("userId") long userId, @Param("orderId") long orderId, @Param("code") String code);

    OrderRow selectOrderForNotify(@Param("code") String code);

    long countUsedCards(@Param("userId") long userId);

    List<CardRow> selectUsedCards(@Param("userId") long userId, @Param("offset") long offset,
                                  @Param("limit") int limit);

    /** 卡号、卡密相等（MySQL 比较规则：不分大小写、忽略尾部空格）且未使用的卡，按 card_id，最多 {@code limit} 张。 */
    List<CardRow> selectUnusedCards(@Param("cardNo") String cardNo, @Param("cardPwd") String cardPwd,
                                    @Param("limit") int limit);

    /** @param level 1～3：按 user_pid、user_pid_2、user_pid_3 */
    long countInvitees(@Param("level") int level, @Param("userId") long userId);

    List<InviteeRow> selectInvitees(@Param("level") int level, @Param("userId") long userId,
                                    @Param("offset") long offset, @Param("limit") int limit);

    /** 全部会员组（旧 {@code Group::getCache}，不看状态）。 */
    List<GroupPriceRow> selectGroups();

    long countVisitsSince(@Param("userId") long userId, @Param("ip") long ip, @Param("since") long since);
}

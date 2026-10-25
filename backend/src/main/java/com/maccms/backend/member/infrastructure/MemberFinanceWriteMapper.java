package com.maccms.backend.member.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.Collection;
import org.apache.ibatis.annotations.Param;

/**
 * 会员权益与财务的写入（B10，19 文档第 6 节）。SQL 见 {@code resources/mapper-write/member/MemberFinanceWriteMapper.xml}；只能经
 * {@code WriteDatabase.mapper()} 取得。每个方法返回<b>匹配</b>行数，调用方与预期比较；多表写入由 application 层按
 * {@code WriteSequence} 组合（认领 → 转移 → 记录，失败时条件补偿）。
 */
@WriteMapper
public interface MemberFinanceWriteMapper {

    // ---------------------------------------------------------------- mac_ulog

    int claimUlog(@Param("userId") long userId, @Param("mid") int mid, @Param("type") int type,
                  @Param("rid") long rid, @Param("sid") int sid, @Param("nid") int nid, @Param("points") int points,
                  @Param("now") long now);

    int touchUlog(@Param("userId") long userId, @Param("mid") int mid, @Param("type") int type,
                  @Param("rid") long rid, @Param("sid") int sid, @Param("nid") int nid, @Param("points") int points,
                  @Param("now") long now);

    int releaseUlog(@Param("userId") long userId, @Param("mid") int mid, @Param("type") int type,
                    @Param("rid") long rid, @Param("sid") int sid, @Param("nid") int nid, @Param("points") int points,
                    @Param("now") long now);

    /** @param ids 为 null 时删除该会员该类型的全部 */
    int deleteUlogs(@Param("userId") long userId, @Param("type") int type, @Param("ids") Collection<Long> ids);

    // ---------------------------------------------------------------- mac_plog

    int insertPlog(@Param("userId") long userId, @Param("type") int type, @Param("points") long points,
                   @Param("now") long now, @Param("remarks") String remarks);

    /** @param ids 为 null 时删除该会员的全部 */
    int deletePlogs(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    // ---------------------------------------------------------------- mac_user

    int deductPoints(@Param("userId") long userId, @Param("points") long points);

    int addPoints(@Param("userId") long userId, @Param("points") long points);

    int freezePoints(@Param("userId") long userId, @Param("points") long points);

    int unfreezePoints(@Param("userId") long userId, @Param("points") long points);

    int upgradeGroup(@Param("userId") long userId, @Param("points") long points, @Param("endTime") long endTime,
                     @Param("groupId") int groupId, @Param("oldEndTime") long oldEndTime,
                     @Param("oldGroupId") int oldGroupId);

    int revertUpgrade(@Param("userId") long userId, @Param("points") long points, @Param("endTime") long endTime,
                      @Param("groupId") int groupId, @Param("oldEndTime") long oldEndTime,
                      @Param("oldGroupId") int oldGroupId);

    int expireMembers(@Param("now") long now);

    // ---------------------------------------------------------------- mac_card

    int claimCard(@Param("cardId") long cardId, @Param("userId") long userId, @Param("now") long now);

    int releaseCard(@Param("cardId") long cardId, @Param("userId") long userId, @Param("now") long now,
                    @Param("oldSaleStatus") int oldSaleStatus, @Param("oldUserId") long oldUserId,
                    @Param("oldUseTime") long oldUseTime);

    // ---------------------------------------------------------------- mac_cash

    int insertCash(@Param("userId") long userId, @Param("points") long points, @Param("money") String money,
                   @Param("bankName") String bankName, @Param("bankNo") String bankNo,
                   @Param("payeeName") String payeeName, @Param("now") long now);

    int deleteCash(@Param("cashId") long cashId, @Param("userId") long userId, @Param("status") int status);

    int restoreCash(@Param("row") CashRow row);

    // ---------------------------------------------------------------- mac_order

    int insertOrder(@Param("userId") long userId, @Param("code") String code, @Param("price") String price,
                    @Param("now") long now, @Param("points") long points);

    // ---------------------------------------------------------------- mac_visit

    int insertVisit(@Param("userId") long userId, @Param("ip") long ip, @Param("referer") String referer,
                    @Param("now") long now, @Param("since") long since, @Param("max") long max);
}

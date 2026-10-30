package com.maccms.backend.member.application;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.WriteConflictException;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.member.infrastructure.WalletRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三级分销奖励（旧 {@code User::reward}，common/model/User.php:894-950；19 文档第 4.12 节），在购买与升级成功后调用。每一级是独立的
 * "推广人加积分 → 记流水"序列：推广人不存在（加积分匹配 0 行，旧版 {@code setInc} 返回 0）时跳过这一级；某一级失败不影响其他级与
 * 主操作（旧版同样忽略），失败按 B07 的规则补偿或上报。日志不记录会员与积分。
 */
final class MemberRewards {

    private static final Logger log = LoggerFactory.getLogger(MemberRewards.class);

    private final MemberFinanceWriteMapper writer;
    private final MemberFinanceSettings settings;

    MemberRewards(MemberFinanceWriteMapper writer, MemberFinanceSettings settings) {
        this.writer = writer;
        this.settings = settings;
    }

    /**
     * @param buyer 消费的会员（推广关系与用户名取写库前读到的行）
     * @param fee   本次消费的积分
     */
    void pay(WalletRow buyer, long fee, long now) {
        if (fee <= 0 || !settings.rewardOn()) {
            return;
        }
        long[] pids = {FinanceSupport.nz(buyer.pid()), FinanceSupport.nz(buyer.pid2()), FinanceSupport.nz(buyer.pid3())};
        for (int level = 1; level <= 3; level++) {
            Double ratio = settings.rewardRatio(level);
            long pid = pids[level - 1];
            if (ratio == null || pid == 0) {
                continue;
            }
            // 旧版 floor($fee_points / 100 * $ratio)：先除 100 再乘比例（浮点运算顺序相同）
            long points = PhpNumbers.intval(Math.floor(fee / 100.0 * ratio));
            if (points <= 0) {
                continue;
            }
            String remarks = "用户【" + buyer.userId() + "、" + FinanceSupport.str(buyer.userName()) + "】消费" + fee
                    + "积分，获得奖励" + points + "积分";
            int type = 3 + level;
            try {
                WriteSequence.named("member.reward")
                        .step(WriteSequence.Role.TRANSFER, "points", () -> writer.addPoints(pid, points), 1,
                                () -> writer.deductPoints(pid, points))
                        .step(WriteSequence.Role.RECORD, "plog", () -> writer.insertPlog(pid, type, points, now, remarks),
                                1, null)
                        .build().run();
            } catch (WriteConflictException e) {
                // 推广人已不存在：旧版跳过（不写流水）
            } catch (RuntimeException e) {
                log.warn("member reward level failed: {}", SafeExceptionLog.typeChain(e));
            }
        }
    }
}

package com.maccms.backend.member.application;

import static com.maccms.backend.member.application.MemberAdminSupport.guard;
import static com.maccms.backend.member.application.MemberAdminSupport.like;
import static com.maccms.backend.member.application.MemberAdminSupport.looseEquals;
import static com.maccms.backend.member.application.MemberAdminSupport.nonEmpty;
import static com.maccms.backend.member.application.MemberAdminSupport.notBlank;
import static com.maccms.backend.member.application.MemberAdminSupport.wd;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.CompensationFailedException;
import com.maccms.backend.common.write.WriteConflictException;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.member.infrastructure.CashRow;
import com.maccms.backend.member.infrastructure.MemberAdminCriteria;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWrites;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台订单与提现管理（B14，23 文档第 7.4、7.5 节）：旧 {@code admin/controller/Order.php}、{@code Cash.php} →
 * {@code common/model/Order.php}、{@code Cash.php}。
 *
 * <p>提现的审核与删除涉及 {@code mac_cash} 与会员的冻结积分两张表（MyISAM，没有事务）：每一行是 B07 的 {@link WriteSequence}——认领
 * （按读到的状态条件写 {@code mac_cash}）→ 转移（条件增减冻结积分），转移不命中时按相反顺序补偿认领。旧版用读到的旧值整体覆盖积分、审核时不看
 * 状态（已审核的再审核会再扣一次冻结积分），并发或数据不一致时会丢失或多扣（D-B14-1、D-B14-2）。
 */
public final class FinanceAdminService {

    private static final Logger log = LoggerFactory.getLogger(FinanceAdminService.class);
    /** "清空"时每批处理的提现行数。 */
    static final int CASH_BATCH = 500;
    /** 旧版写死的提示（common/model/Cash.php:142、182、186，不是语言包）。 */
    static final String POINTS_ERR = "更新用户积分失败：";
    static final String AUDIT_OK = "审核成功";

    private final MemberAdminMapper reader;
    private final MemberAdminWrites writer;
    private final Clock clock;

    public FinanceAdminService(MemberAdminMapper reader, MemberAdminWrites writer, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 订单

    /**
     * 订单列表（旧 Order::index）：{@code $param['status'] != ''} 时按状态（原值）、会员（{@code !empty}）、单号关键字；按编号倒序，另带会员名。
     * 旧版没有修改订单状态的后台动作（auth.php 里只有列表与删除），新版同样没有（23 文档第 1 节）。
     */
    public AdminRowPage orders(PhpForm p, AdminPaging paging) {
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.status = notBlank(p, "status");
        c.uid = nonEmpty(p, "uid");
        String wd = wd(p);
        c.like = like(wd);
        return MemberAdminSupport.page(paging, wd, () -> reader.countOrders(c), (o, l) -> reader.listOrders(c, o, l));
    }

    public AdminResult deleteOrders(PhpForm p) {
        return CardAdminService.deleteLike(p, writer::deleteAllOrders, writer::deleteOrders);
    }

    // ------------------------------------------------------------------ 提现

    /** 提现列表（旧 Cash::index）：状态（{@code != ''}，原值）、会员、银行账号关键字；按编号倒序，另带会员名。 */
    public AdminRowPage cashes(PhpForm p, AdminPaging paging) {
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.status = notBlank(p, "status");
        c.uid = nonEmpty(p, "uid");
        String wd = wd(p);
        c.like = like(wd);
        return MemberAdminSupport.page(paging, wd, () -> reader.countCashes(c), (o, l) -> reader.listCashes(c, o, l));
    }

    /**
     * 审核（旧 Cash::audit → {@code Cash::auditData}）：{@code ids} 为空 → "参数错误"。逐行：未审核的标为已审核、写审核时间（认领，
     * {@code WHERE cash_status = 0}），再扣减会员的冻结积分（{@code WHERE user_points_froze >= cash_points}）；扣减不命中时撤销这一行的审核并返回
     * "更新用户积分失败："（旧版先改状态再扣，扣成负数时 MySQL 报错 500，提现已标为已审核）。已审核的行跳过（旧版再写一次审核时间、再扣一次冻结积分）；
     * 会员已不存在时只审核（旧版的扣减语句匹配 0 行、照常成功）。全部完成："审核成功"。
     */
    public AdminResult audit(PhpForm p) {
        if (p.empty("ids")) {
            return AdminResult.error("param_err");
        }
        return guard(() -> {
            List<Long> ids = AdminIds.ids(p);
            for (CashRow row : reader.selectCashes(ids, 0, AdminIds.MAX_IDS)) {
                if (nz(row.status()) != 0) {
                    continue;
                }
                long cashId = row.cashId();
                long userId = nz(row.userId());
                long points = nz(row.points());
                long now = clock.instant().getEpochSecond();
                long oldAudit = row.timeAudit() == null ? 0 : row.timeAudit();
                boolean userExists = reader.countUser(userId) > 0;
                WriteSequence.Builder seq = WriteSequence.named("admin.cash_audit")
                        .step(WriteSequence.Role.CLAIM, "cash", () -> writer.auditCash(cashId, now), 1,
                                () -> writer.revertAudit(cashId, now, oldAudit));
                if (userExists) {
                    seq.step(WriteSequence.Role.TRANSFER, "froze", () -> writer.settleFrozen(userId, points), 1, null);
                }
                try {
                    seq.build().run();
                } catch (WriteConflictException e) {
                    if (!e.step().equals("cash")) {
                        return AdminResult.error(AdminMessage.text(POINTS_ERR));
                    }
                    // 期间已被别人审核或删除：跳过
                } catch (CompensationFailedException e) {
                    return AdminResult.error(AdminMessage.text(POINTS_ERR));
                }
            }
            return AdminResult.ok(AdminMessage.text(AUDIT_OK));
        });
    }

    /**
     * 删除（旧 Cash::del → {@code Cash::delData}，common/model/Cash.php:117-146）：{@code ids} 为空 → "参数错误"；{@code all == 1} 时全部（按批），
     * 否则按 {@code ids}。逐行：已审核的只删除；未审核的删除（认领，按读到的状态条件删）后把冻结积分退回可用积分
     * （{@code WHERE user_points_froze >= cash_points}），退回不命中时把这一行原样插回并返回"更新用户积分失败："（旧版先删再整体覆盖积分，
     * 冻结积分不够时 MySQL 报错 500，提现记录已删、积分没有退回）；会员已不存在时只删除（同旧版）。与会员中心撤销提现（B10）同样的语句。
     */
    public AdminResult deleteCashes(PhpForm p) {
        if (p.empty("ids")) {
            return AdminResult.error("param_err");
        }
        return guard(() -> {
            boolean everything = looseEquals(p, "all", 1);
            List<Long> ids = everything ? null : AdminIds.ids(p);
            long after = 0;
            while (true) {
                List<CashRow> rows = everything ? reader.selectCashes(null, after, CASH_BATCH)
                        : reader.selectCashes(ids, 0, AdminIds.MAX_IDS);
                for (CashRow row : rows) {
                    AdminResult failed = deleteOne(row);
                    if (failed != null) {
                        return failed;
                    }
                    after = Math.max(after, row.cashId());
                }
                if (!everything || rows.size() < CASH_BATCH) {
                    break;
                }
            }
            return AdminResult.ok("del_ok");
        });
    }

    /** @return 失败时的结果；成功（含已被别人处理）时为 null */
    private AdminResult deleteOne(CashRow row) {
        long cashId = row.cashId();
        long userId = nz(row.userId());
        int status = nz(row.status());
        long points = nz(row.points());
        if (status != 0) {
            writer.deleteCash(cashId, userId, status);
            return null;
        }
        boolean userExists = reader.countUser(userId) > 0;
        WriteSequence.Builder seq = WriteSequence.named("admin.cash_del")
                .step(WriteSequence.Role.CLAIM, "cash", () -> writer.deleteCash(cashId, userId, 0), 1,
                        () -> writer.restoreCash(row));
        if (userExists) {
            seq.step(WriteSequence.Role.TRANSFER, "unfreeze", () -> writer.unfreezePoints(userId, points), 1, null);
        }
        try {
            seq.build().run();
            return null;
        } catch (WriteConflictException e) {
            if (e.step().equals("cash")) {
                // 已被并发删除（行不在了），或期间被审核（状态变了）：已审核的按新状态只删除，不退冻结
                List<CashRow> again = reader.selectCashes(List.of(cashId), 0, 1);
                if (!again.isEmpty() && nz(again.get(0).status()) != 0) {
                    writer.deleteCash(cashId, userId, nz(again.get(0).status()));
                }
                return null;
            }
            return AdminResult.error(AdminMessage.text(POINTS_ERR));
        } catch (CompensationFailedException e) {
            log.warn("admin cash delete needs reconciliation: {}", SafeExceptionLog.typeChain(e));
            return AdminResult.error(AdminMessage.text(POINTS_ERR));
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}

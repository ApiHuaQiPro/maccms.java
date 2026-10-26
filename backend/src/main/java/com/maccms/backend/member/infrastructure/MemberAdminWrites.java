package com.maccms.backend.member.infrastructure;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 后台会员与财务写入的执行（B14，23 文档第 6 节）：经 {@link MemberAdminWriteMapper}（与 B10 的 {@link MemberFinanceWriteMapper}）执行，
 * 把 MySQL 严格模式下的数据错误换成 {@link MemberAdminDataException}，其余异常原样抛出（与 B13 的后台写入相同）。返回匹配行数。
 */
public final class MemberAdminWrites {

    /** 数据错误：1048 列不能为 NULL、1264 超出范围、1265 截断、1292 值不正确、1364 没有默认值的列未给值、1366 整数/字符串值不正确、1406 过长。 */
    private static final Set<Integer> DATA_ERRORS = Set.of(1048, 1264, 1265, 1292, 1364, 1366, 1406);

    private final MemberAdminWriteMapper mapper;
    private final MemberFinanceWriteMapper finance;

    public MemberAdminWrites(MemberAdminWriteMapper mapper, MemberFinanceWriteMapper finance) {
        this.mapper = mapper;
        this.finance = finance;
    }

    // ---------------------------------------------------------------- mac_user

    /** 新增会员，返回自增主键。 */
    public long insertUser(Map<String, String> columns) {
        MemberAdminInsert row = new MemberAdminInsert(columns);
        run(() -> mapper.insertUser(row));
        return row.getId() == null ? 0 : row.getId();
    }

    /** 按主键整行覆盖（只写给出的列）；没有列时不执行。 */
    public int updateUser(Map<String, String> columns, long id) {
        return columns.isEmpty() ? 0 : run(() -> mapper.updateUser(columns, id));
    }

    public int updateUsers(Map<String, String> columns, List<Long> ids) {
        return columns.isEmpty() || ids.isEmpty() ? 0 : run(() -> mapper.updateUserByIds(columns, ids));
    }

    public int deleteUsers(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteUsers(ids));
    }

    /** 到期批量降级（B10 的同一条语句，{@code user_end_time <= now}）。 */
    public int expireMembers(long now) {
        return run(() -> finance.expireMembers(now));
    }

    // ---------------------------------------------------------------- mac_group

    public long insertGroup(Map<String, String> columns) {
        MemberAdminInsert row = new MemberAdminInsert(columns);
        run(() -> mapper.insertGroup(row));
        return row.getId() == null ? 0 : row.getId();
    }

    public int updateGroup(Map<String, String> columns, long id) {
        return columns.isEmpty() ? 0 : run(() -> mapper.updateGroup(columns, id));
    }

    public int updateGroups(Map<String, String> columns, List<Long> ids) {
        return columns.isEmpty() || ids.isEmpty() ? 0 : run(() -> mapper.updateGroupByIds(columns, ids));
    }

    public int deleteEmptyGroups(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteEmptyGroups(ids));
    }

    // ---------------------------------------------------------------- mac_card

    public int insertCards(List<CardInsert> rows, long money, long points, long now) {
        return rows.isEmpty() ? 0 : run(() -> mapper.insertCards(rows, money, points, now));
    }

    public int deleteCards(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteCards(ids));
    }

    public int deleteAllCards() {
        return run(mapper::deleteAllCards);
    }

    // ---------------------------------------------------------------- mac_order

    public int deleteOrders(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteOrders(ids));
    }

    public int deleteAllOrders() {
        return run(mapper::deleteAllOrders);
    }

    // ---------------------------------------------------------------- mac_cash（审核：本批；撤销的认领、补偿与退回冻结：B10 的语句）

    public int auditCash(long cashId, long now) {
        return run(() -> mapper.auditCash(cashId, now));
    }

    public int revertAudit(long cashId, long now, long oldAudit) {
        return run(() -> mapper.revertAudit(cashId, now, oldAudit));
    }

    public int settleFrozen(long userId, long points) {
        return run(() -> mapper.settleFrozen(userId, points));
    }

    public int deleteCash(long cashId, long userId, int status) {
        return run(() -> finance.deleteCash(cashId, userId, status));
    }

    public int restoreCash(CashRow row) {
        return run(() -> finance.restoreCash(row));
    }

    public int unfreezePoints(long userId, long points) {
        return run(() -> finance.unfreezePoints(userId, points));
    }

    // ---------------------------------------------------------------- mac_plog / mac_ulog / mac_visit

    public int deletePlogs(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deletePlogs(ids));
    }

    public int deleteAllPlogs() {
        return run(mapper::deleteAllPlogs);
    }

    public int deleteUlogs(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteUlogs(ids));
    }

    public int deleteAllUlogs() {
        return run(mapper::deleteAllUlogs);
    }

    public int deleteVisits(List<Long> ids) {
        return ids.isEmpty() ? 0 : run(() -> mapper.deleteVisits(ids));
    }

    public int deleteAllVisits() {
        return run(mapper::deleteAllVisits);
    }

    private static <T> T run(Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s && DATA_ERRORS.contains(s.getErrorCode())) {
                    throw new MemberAdminDataException();
                }
                if (t.getCause() == t) {
                    break;
                }
            }
            throw e;
        }
    }
}

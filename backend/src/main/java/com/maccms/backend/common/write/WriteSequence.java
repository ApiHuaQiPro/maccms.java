package com.maccms.backend.common.write;

import com.maccms.backend.common.web.SafeExceptionLog;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多表写入的固定顺序与补偿（B07，docs/architecture/12-write-infrastructure.md 第 4.4 节）。旧表是 MyISAM，没有事务，
 * 多条语句之间的一致性只能靠"固定顺序 + 每步检查匹配行数 + 失败时按相反顺序补偿"保证。
 *
 * <p>步骤按角色排序，顺序在构建时检查：
 * <ol>
 * <li>{@link Role#CLAIM 认领}（最多一步，必须最先）：只有一个并发请求能成功的条件写入，例如
 * {@code UPDATE mac_card SET card_use_status=1 ... WHERE card_id=? AND card_use_status=0}。它同时是幂等键：重复请求在这一步
 * 匹配 0 行，什么都没改就结束。</li>
 * <li>{@link Role#TRANSFER 转移}：余额、积分、计数等数值变化，一律原子表达式加条件（{@code user_points = user_points - ?
 * WHERE ... AND user_points >= ?}）。</li>
 * <li>{@link Role#RECORD 记录}：日志、流水等追加记录（{@code mac_plog}、{@code mac_ulog} 等）。</li>
 * </ol>
 *
 * <p>执行规则：
 * <ul>
 * <li>每步返回匹配行数（可写数据源固定 {@code useAffectedRows=false}）。匹配 0 行说明这一步什么都没改：已完成的步骤按相反顺序补偿，
 * 全部补偿成功后抛 {@link WriteConflictException}。</li>
 * <li>匹配行数非 0 但不等于预期（部分生效或超额匹配）：这一步已经改了数据，但改了哪些行、能否撤销都不确定。不做任何补偿，
 * 已完成步骤（包括认领）原样保留，抛 {@link CompensationFailedException}（{@code UNEXPECTED_ROWS}）。</li>
 * <li>某步抛出异常时，先判断语句是否肯定没有执行（{@link #definitelyNotApplied}）：肯定没有执行时同样补偿已完成步骤后重新抛出；
 * 不能确定时（连接中断、超时、被中止、客户端错误）不补偿，抛 {@link CompensationFailedException}（{@code OUTCOME_UNKNOWN}）。</li>
 * <li>补偿语句同样检查匹配行数。某个补偿不命中或抛异常时<b>立即停止</b>，不再补偿更早的步骤：后面的步骤依赖前面的步骤，
 * 前面的步骤（尤其是认领）必须保留，作为未对账前不能再次生效的屏障。抛 {@link CompensationFailedException}
 * （{@code COMPENSATION_FAILED}）。</li>
 * </ul>
 * 三种 {@link CompensationFailedException} 都列出全部待对账步骤（按执行顺序），日志里只记序列名、步骤名、原因与异常类型。
 * 补偿是对本次差值的条件逆操作，不是恢复到写入前的快照：期间其他请求的写入照常保留。
 * 每个步骤应该是一条单行语句（按主键或认领条件）；预期大于 1 行的步骤出错时一律视为不确定（MyISAM 多行语句出错会留下部分结果）。
 */
public final class WriteSequence {

    private static final Logger log = LoggerFactory.getLogger(WriteSequence.class);

    /** MySQL 中表示语句被中止、超时或服务端关闭的错误码：语句可能已部分执行。 */
    private static final Set<Integer> INTERRUPTED = Set.of(1028, 1053, 1317, 1927, 3024);

    public enum Role { CLAIM, TRANSFER, RECORD }

    private record Step(Role role, String name, IntSupplier write, int expected, IntSupplier compensation) {
    }

    private final String name;
    private final List<Step> steps;

    private WriteSequence(String name, List<Step> steps) {
        this.name = name;
        this.steps = List.copyOf(steps);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /**
     * 单条条件写入的匹配行数检查：匹配 0 行抛 {@link WriteConflictException}；非 0 但不等于预期（部分生效或超额匹配）抛
     * {@link CompensationFailedException}（{@code UNEXPECTED_ROWS}）并记日志，不能当作可重试的普通冲突。
     */
    public static void requireMatched(String what, int matched, int expected) {
        if (matched == expected) {
            return;
        }
        if (matched == 0) {
            throw new WriteConflictException(what, what, matched, expected);
        }
        throw logged(new CompensationFailedException(what, what, CompensationFailedException.Reason.UNEXPECTED_ROWS,
                List.of(what), null));
    }

    /** 按顺序执行全部步骤；规则见类注释。 */
    public void run() {
        List<Step> done = new ArrayList<>();
        for (Step step : steps) {
            int matched;
            try {
                matched = step.write().getAsInt();
            } catch (RuntimeException e) {
                if (step.expected() > 1 || !definitelyNotApplied(e)) {
                    throw logged(new CompensationFailedException(name, step.name(),
                            CompensationFailedException.Reason.OUTCOME_UNKNOWN, namesWith(done, step), e));
                }
                compensate(done, step, e);
                throw e;
            }
            if (matched == 0) {
                compensate(done, step, null);
                throw new WriteConflictException(name, step.name(), matched, step.expected());
            }
            if (matched != step.expected()) {
                throw logged(new CompensationFailedException(name, step.name(),
                        CompensationFailedException.Reason.UNEXPECTED_ROWS, namesWith(done, step), null));
            }
            done.add(step);
        }
    }

    /** 按相反顺序补偿；遇到第一个失败的补偿即停止，它和更早的步骤都留给人工对账。 */
    private void compensate(List<Step> done, Step failed, Throwable cause) {
        for (int i = done.size() - 1; i >= 0; i--) {
            Step s = done.get(i);
            RuntimeException error = null;
            int matched = -1;
            try {
                matched = s.compensation().getAsInt();
            } catch (RuntimeException e) {
                error = e;
            }
            if (error == null && matched == s.expected()) {
                continue;
            }
            Throwable first = cause;
            if (first == null) {
                first = error;
            } else if (error != null && error != first) {
                first.addSuppressed(error);
            }
            throw logged(new CompensationFailedException(name, failed.name(),
                    CompensationFailedException.Reason.COMPENSATION_FAILED, names(done.subList(0, i + 1)), first));
        }
    }

    private static CompensationFailedException logged(CompensationFailedException e) {
        log.error("写入需人工对账：sequence={} step={} reason={} unresolved={} cause={}", e.sequence(), e.failedStep(),
                e.reason(), e.unresolvedSteps(), e.getCause() == null ? "-" : SafeExceptionLog.typeChain(e.getCause()));
        return e;
    }

    private static List<String> names(List<Step> steps) {
        return steps.stream().map(Step::name).toList();
    }

    private static List<String> namesWith(List<Step> done, Step current) {
        List<String> all = new ArrayList<>(names(done));
        all.add(current.name());
        return all;
    }

    /**
     * 异常是否说明语句肯定没有执行：执行前被本应用拒绝（{@link WriteRejectedException}），或服务端返回了错误（SQLException
     * 带服务端错误码，且不是连接类 SQLState {@code 08xxx}、超时或中止）。其余一律视为不确定。
     */
    static boolean definitelyNotApplied(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof WriteRejectedException) {
                return true;
            }
            if (t instanceof SQLException s) {
                if (s instanceof SQLTimeoutException) {
                    return false;
                }
                String state = s.getSQLState();
                int code = s.getErrorCode();
                // 2000～2999 是客户端错误码（CR_*），不说明服务端是否执行过
                boolean serverError = code > 0 && (code < 2000 || code >= 3000);
                return serverError && state != null && !state.startsWith("08") && !INTERRUPTED.contains(code);
            }
        }
        return false;
    }

    public static final class Builder {

        private final String name;
        private final List<Step> steps = new ArrayList<>();
        private final Set<String> stepNames = new HashSet<>();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("写入序列必须有名称");
            }
            this.name = name;
        }

        /**
         * 加一步。{@code write} 返回匹配行数，{@code expected} 为预期行数（≥1）；{@code compensation} 撤销这一步，返回匹配行数，
         * 预期同为 {@code expected}。最后一步可以没有补偿（传 null），其他步骤必须有。
         */
        public Builder step(Role role, String stepName, IntSupplier write, int expected, IntSupplier compensation) {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(write, "write");
            if (stepName == null || stepName.isBlank() || !stepNames.add(stepName)) {
                throw new IllegalArgumentException("步骤名为空或重复");
            }
            if (expected < 1) {
                throw new IllegalArgumentException("预期匹配行数至少为 1");
            }
            if (!steps.isEmpty()) {
                Step last = steps.get(steps.size() - 1);
                if (last.compensation() == null) {
                    throw new IllegalArgumentException("只有最后一步可以没有补偿：" + last.name());
                }
                if (role.ordinal() < last.role().ordinal() || role == Role.CLAIM) {
                    throw new IllegalArgumentException("步骤顺序必须是 CLAIM（最多一步）→ TRANSFER → RECORD：" + stepName);
                }
            }
            steps.add(new Step(role, stepName, write, expected, compensation));
            return this;
        }

        public WriteSequence build() {
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("写入序列至少有一步");
            }
            return new WriteSequence(name, steps);
        }
    }
}

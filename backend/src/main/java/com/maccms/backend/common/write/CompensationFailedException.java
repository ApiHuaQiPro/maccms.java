package com.maccms.backend.common.write;

import java.util.List;

/**
 * 写入中途失败且无法自动恢复，需要人工对账（docs/architecture/12-write-infrastructure.md 第 4.4 节）。三种情形：
 * <ul>
 * <li>{@link Reason#COMPENSATION_FAILED}：某一步失败后，补偿已完成步骤时有补偿语句没有命中预期行数或抛了异常。补偿在这里停止，
 * 更早的步骤（包括认领）保持已生效，作为未对账前不能再次生效的屏障；</li>
 * <li>{@link Reason#OUTCOME_UNKNOWN}：某一步抛出的异常不能确定语句是否已在服务端执行（连接中断、超时、被中止），
 * 此时不做补偿（补偿可能把已生效的写入变成不一致）；</li>
 * <li>{@link Reason#UNEXPECTED_ROWS}：某一步匹配行数非 0 但不等于预期（部分生效或超额匹配），这一步已经改了数据，不做补偿。</li>
 * </ul>
 * 不是可重试的普通冲突，调用方不得自动重试。message 只含序列名、步骤名与原因，不含参数值；cause 可能含 SQL 与参数，
 * 记录日志时只能用 {@code SafeExceptionLog}。
 */
public class CompensationFailedException extends RuntimeException {

    public enum Reason { COMPENSATION_FAILED, OUTCOME_UNKNOWN, UNEXPECTED_ROWS }

    private final String sequence;
    private final String failedStep;
    private final Reason reason;
    private final List<String> unresolvedSteps;

    public CompensationFailedException(String sequence, String failedStep, Reason reason, List<String> unresolvedSteps,
                                       Throwable cause) {
        super("写入需人工对账：" + sequence + "/" + failedStep + " " + reason + "，未恢复的步骤 " + unresolvedSteps, cause);
        this.sequence = sequence;
        this.failedStep = failedStep;
        this.reason = reason;
        this.unresolvedSteps = List.copyOf(unresolvedSteps);
    }

    public String sequence() {
        return sequence;
    }

    public String failedStep() {
        return failedStep;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * 待人工对账的全部步骤，按执行顺序：COMPENSATION_FAILED 时为补偿失败的步骤及其之前所有未补偿的步骤（之后的步骤已补偿成功，
     * 出错步骤本身没有生效）；OUTCOME_UNKNOWN 与 UNEXPECTED_ROWS 时为出错步骤及其之前的全部步骤。
     */
    public List<String> unresolvedSteps() {
        return unresolvedSteps;
    }
}

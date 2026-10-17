package com.maccms.backend.common.write;

import java.util.List;

/**
 * 多表写入中途失败且无法自动恢复，需要人工对账（docs/architecture/12-write-infrastructure.md 第 4.4 节）。两种情形：
 * <ul>
 * <li>{@link Reason#COMPENSATION_FAILED}：某一步失败后，已完成步骤中有补偿语句没有命中预期行数或抛了异常；</li>
 * <li>{@link Reason#OUTCOME_UNKNOWN}：某一步抛出的异常不能确定语句是否已在服务端执行（连接中断、超时、被中止），
 * 此时不做补偿（补偿可能把已生效的写入变成不一致）。</li>
 * </ul>
 * message 只含序列名、步骤名与原因，不含参数值；cause 可能含 SQL 与参数，记录日志时只能用 {@code SafeExceptionLog}。
 */
public class CompensationFailedException extends RuntimeException {

    public enum Reason { COMPENSATION_FAILED, OUTCOME_UNKNOWN }

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

    /** 状态不确定、需要人工核对的步骤（COMPENSATION_FAILED 时为补偿失败的步骤；OUTCOME_UNKNOWN 时为出错步骤及其之前的全部步骤）。 */
    public List<String> unresolvedSteps() {
        return unresolvedSteps;
    }
}

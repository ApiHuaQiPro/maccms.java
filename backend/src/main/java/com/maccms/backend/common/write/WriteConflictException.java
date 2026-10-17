package com.maccms.backend.common.write;

/**
 * 条件写入的匹配行数与预期不符（例如卡已被别人用掉、余额不足、状态已变）。抛出时本次已执行的步骤都已补偿完毕，
 * 数据回到本次写入之前；调用方据此返回业务错误，或在幂等场景下重读确认"已经完成"。
 */
public class WriteConflictException extends RuntimeException {

    private final String sequence;
    private final String step;
    private final int matched;
    private final int expected;

    public WriteConflictException(String sequence, String step, int matched, int expected) {
        super("条件写入未命中：" + sequence + "/" + step + " 匹配 " + matched + " 行，预期 " + expected + " 行");
        this.sequence = sequence;
        this.step = step;
        this.matched = matched;
        this.expected = expected;
    }

    public String sequence() {
        return sequence;
    }

    public String step() {
        return step;
    }

    public int matched() {
        return matched;
    }

    public int expected() {
        return expected;
    }
}

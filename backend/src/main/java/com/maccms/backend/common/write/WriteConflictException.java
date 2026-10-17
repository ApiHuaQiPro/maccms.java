package com.maccms.backend.common.write;

/**
 * 条件写入匹配 0 行（例如卡已被别人用掉、余额不足、状态已变）：出错的这一步什么都没改，本次此前已执行的步骤都已按条件补偿成功。
 * 补偿是对本次差值的逆操作，不是恢复到写入前的快照，期间其他请求的写入照常保留。调用方据此返回业务错误，或在幂等场景下重读确认
 * "已经完成"。匹配行数非 0 但不等于预期、或补偿失败时抛的是 {@link CompensationFailedException}，不是本异常。
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

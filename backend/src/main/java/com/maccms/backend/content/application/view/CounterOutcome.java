package com.maccms.backend.content.application.view;

/**
 * 计数操作的结果（B11）：{@code FOUND} 带计数；{@code MISSING} 为按主键（来路为按域名）找不到内容（旧版"获取失败"）；
 * {@code REJECTED} 为写入被数据库拒绝（计数已到列的上限、评分超出 {@code decimal(3,1)} 等，旧版 500，没有写入）。
 */
public record CounterOutcome<T>(Status status, T counts) {

    public enum Status { FOUND, MISSING, REJECTED }

    public static <T> CounterOutcome<T> found(T counts) {
        return new CounterOutcome<>(Status.FOUND, counts);
    }

    public static <T> CounterOutcome<T> missing() {
        return new CounterOutcome<>(Status.MISSING, null);
    }

    public static <T> CounterOutcome<T> rejected() {
        return new CounterOutcome<>(Status.REJECTED, null);
    }
}

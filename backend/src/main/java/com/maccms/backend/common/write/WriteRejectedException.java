package com.maccms.backend.common.write;

/**
 * 写语句在执行前被拒绝：不符合写 SQL 规范（{@link WriteStatement}），或"模块 + 表 + 操作"未登记（{@link WriteRegistry}）。
 * 语句没有发送到数据库。message 只含模块、表、操作与原因，不含参数值。
 */
public class WriteRejectedException extends RuntimeException {

    public WriteRejectedException(String message) {
        super(message);
    }
}

package com.maccms.backend.common.write;

/** 登记表中的写操作。不含 REPLACE、结构语句与表维护语句（{@link WriteStatement} 一律拒绝）。 */
public enum WriteOperation {
    INSERT, UPDATE, DELETE
}

package com.maccms.backend.content.infrastructure;

/**
 * MySQL 拒绝了写入的取值（严格模式下的数据错误：超出范围、不是数字、过长、非法字符、NOT NULL 列写 NULL）。旧版在这些情况下整页 500；
 * 后台把它归为"参数错误"（22 文档 D-B13-3）。不带原始异常信息（可能含取值）。
 */
public final class AdminDataException extends RuntimeException {

    public AdminDataException() {
        super(null, null, false, false);
    }
}

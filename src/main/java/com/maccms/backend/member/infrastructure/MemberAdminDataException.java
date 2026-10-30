package com.maccms.backend.member.infrastructure;

/**
 * MySQL 严格模式拒绝了后台会员与财务写入的取值（超出范围、截断、非数字等，B14）：旧版整页 500，新版按"参数错误"处理（与 B13 的
 * {@code AdminDataException} 相同）。不带原始信息（可能含 SQL 参数）。
 */
public final class MemberAdminDataException extends RuntimeException {

    public MemberAdminDataException() {
        super(null, null, false, false);
    }
}

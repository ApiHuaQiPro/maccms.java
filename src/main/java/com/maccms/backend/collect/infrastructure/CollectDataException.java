package com.maccms.backend.collect.infrastructure;

/** MySQL 严格模式拒绝了写入的取值（旧版整页 500；新版"参数错误"，同 B13 的 D-B13-3）。不带原始错误信息（可能含取值）。 */
public final class CollectDataException extends RuntimeException {

    public CollectDataException() {
        super(null, null, false, false);
    }
}

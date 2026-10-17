package com.maccms.backend.common.write;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一条写入权限：模块 {@code module}（{@code com.maccms.backend.<module>.infrastructure} 下的写 Mapper）可以对表 {@code table}
 * 执行 {@code operation}，由批次 {@code batch} 登记。登记规则见 docs/architecture/12-write-infrastructure.md 第 3 节。
 */
public record WritePermission(String module, String table, WriteOperation operation, String batch) {

    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9]*");
    private static final Pattern TABLE = Pattern.compile("mac_[a-z][a-z0-9_]*");
    private static final Pattern BATCH = Pattern.compile("B[0-9]{2}");

    public WritePermission {
        Objects.requireNonNull(operation, "operation");
        if (module == null || !MODULE.matcher(module).matches()) {
            throw new IllegalArgumentException("写入权限的模块名不合法");
        }
        if (table == null || !TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("写入权限的表名不合法");
        }
        if (batch == null || !BATCH.matcher(batch).matches()) {
            throw new IllegalArgumentException("写入权限的批次不合法");
        }
    }
}

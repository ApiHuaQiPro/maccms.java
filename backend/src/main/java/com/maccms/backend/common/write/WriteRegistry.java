package com.maccms.backend.common.write;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "模块 + 表 + 操作"写入登记表（B07，路线图第 5.3 节写入方矩阵；docs/architecture/12-write-infrastructure.md 第 3 节）。
 *
 * <p>同一份登记表有两道检查：静态护栏 {@code WriteGuardTest} 在 {@code ./mvnw verify} 时检查写 Mapper XML 中的每条语句，
 * 运行期由 {@link WriteGuardInterceptor} 在语句执行前再检查一次实际 SQL。未登记的模块、表或操作都会被拒绝。
 */
public final class WriteRegistry {

    /**
     * 已登记的写入权限。B07 交付时为空：本批只建写入基础设施，不实现任何业务写入。B08 起每个写库批次在自己的合并提交里
     * 只登记本批的条目，表与批次必须符合路线图的写入方矩阵（{@code WriteGuardTest.OWNER_MATRIX} 锁定）。
     */
    static final List<WritePermission> REGISTERED = List.of();

    /** 写 Mapper 的全限定名：{@code com.maccms.backend.<module>.infrastructure[.子包].<类型>}。 */
    private static final Pattern MAPPER_TYPE = Pattern.compile(
            "com\\.maccms\\.backend\\.([a-z][a-z0-9]*)\\.infrastructure(?:\\.[a-z][a-z0-9_]*)*\\.[A-Z][A-Za-z0-9_]*");

    private final List<WritePermission> permissions;
    private final Set<String> keys;

    private WriteRegistry(Collection<WritePermission> permissions) {
        this.permissions = List.copyOf(permissions);
        Set<String> k = new LinkedHashSet<>();
        for (WritePermission p : this.permissions) {
            if (!k.add(key(p.module(), p.table(), p.operation()))) {
                throw new IllegalArgumentException("写入权限重复登记：" + p.module() + " " + p.operation() + " " + p.table());
            }
        }
        this.keys = Set.copyOf(k);
    }

    /** 生产使用的登记表（{@link #REGISTERED}）。 */
    public static WriteRegistry registered() {
        return new WriteRegistry(REGISTERED);
    }

    /** 只供一次性库上的演练与测试使用；静态护栏禁止 common.write 以外的正式代码调用。 */
    public static WriteRegistry of(Collection<WritePermission> permissions) {
        return new WriteRegistry(permissions);
    }

    public boolean allows(String module, String table, WriteOperation operation) {
        return keys.contains(key(module, table, operation));
    }

    /** 检查写 Mapper {@code mapperType} 的一条语句；未登记时抛 {@link WriteRejectedException}。 */
    public void check(String mapperType, WriteStatement statement) {
        String module = moduleOf(mapperType);
        if (!allows(module, statement.table(), statement.operation())) {
            throw new WriteRejectedException("未登记的写入：模块 " + module + " 对 " + statement.table() + " 的 "
                    + statement.operation() + "（登记见 WriteRegistry.REGISTERED）");
        }
    }

    /** 写 Mapper 所属模块；不在 {@code <module>.infrastructure} 下时拒绝。 */
    public static String moduleOf(String mapperType) {
        Matcher m = mapperType == null ? null : MAPPER_TYPE.matcher(mapperType);
        if (m == null || !m.matches()) {
            throw new WriteRejectedException("写 Mapper 必须位于 com.maccms.backend.<模块>.infrastructure 包下");
        }
        return m.group(1);
    }

    public List<WritePermission> permissions() {
        return permissions;
    }

    private static String key(String module, String table, WriteOperation operation) {
        return module + "|" + table + "|" + operation;
    }
}

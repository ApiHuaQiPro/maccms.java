package com.maccms.backend.common.write;

import java.util.Locale;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 可写数据源的运行期护栏（B07）：每条语句发送到数据库之前，按实际生成的 SQL 检查写 SQL 规范（{@link WriteStatement}）与
 * "模块 + 表 + 操作"登记（{@link WriteRegistry}），不通过就抛 {@link WriteRejectedException}，语句不会执行。
 * 查询一律拒绝：读取走只读连接池。
 *
 * <p>静态护栏 {@code WriteGuardTest} 已在构建时检查写 Mapper XML；这里补上动态 SQL（{@code <if>} 等）在运行时的实际形态。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class,
                        BoundSql.class}),
        @Signature(type = Executor.class, method = "queryCursor",
                args = {MappedStatement.class, Object.class, RowBounds.class})})
final class WriteGuardInterceptor implements Interceptor {

    private final WriteRegistry registry;

    WriteGuardInterceptor(WriteRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!"update".equals(invocation.getMethod().getName())) {
            throw new WriteRejectedException("可写数据源不执行查询：读取走只读连接池");
        }
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs()[1];
        check(ms.getId(), ms.getSqlCommandType().name(), ms.getBoundSql(parameter).getSql());
        return invocation.proceed();
    }

    /**
     * 检查一条语句：{@code statementId} 为 {@code <Mapper 全限定名>.<语句 id>}，{@code commandType} 为 XML 元素类型
     * （insert/update/delete），{@code sql} 为实际 SQL。
     */
    void check(String statementId, String commandType, String sql) {
        int dot = statementId == null ? -1 : statementId.lastIndexOf('.');
        if (dot < 0) {
            throw new WriteRejectedException("写语句缺少 Mapper 命名空间");
        }
        WriteStatement statement = WriteStatement.parse(sql);
        if (!statement.operation().name().equals(commandType == null ? "" : commandType.toUpperCase(Locale.ROOT))) {
            throw new WriteRejectedException("写语句的元素类型与 SQL 不一致：<" + commandType + "> 中是 "
                    + statement.operation());
        }
        registry.check(statementId.substring(0, dot), statement);
    }
}

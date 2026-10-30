package com.maccms.backend.common.write;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 可写数据源（B07，docs/architecture/12-write-infrastructure.md 第 2 节）：与只读连接池分开的独立连接池（{@code maccms-rw}）
 * 和独立的 MyBatis 会话工厂。
 *
 * <ul>
 * <li>不是 Spring 的 {@code DataSource} Bean：只读连接池、MyBatis 自动配置、健康检查都不受影响，也没有任何代码能经依赖注入
 * 拿到可写连接。写 Mapper 只能经 {@link #mapper(Class)} 取得。</li>
 * <li>只加载 {@code classpath*:mapper-write/**}{@code /*.xml}；每条语句执行前经 {@link WriteGuardInterceptor} 检查写 SQL 规范与登记表，
 * 查询一律拒绝。</li>
 * <li>连接自动提交、不开事务（旧表是 MyISAM，事务不起作用，见 12 文档第 4.3 节）；{@code useAffectedRows=false}，UPDATE 返回匹配行数；
 * 不允许多语句与本地文件导入。</li>
 * <li>启动时取一个连接，确认不是只读会话且当前库与地址中的库名一致；失败时只报异常类型，不回显地址、账号、密码。</li>
 * </ul>
 */
public final class WriteDatabase implements AutoCloseable {

    static final String POOL_NAME = "maccms-rw";
    static final String MAPPER_LOCATIONS = "classpath*:mapper-write/**/*.xml";
    static final int CONNECTION_TIMEOUT_MS = 5000;
    static final int STATEMENT_TIMEOUT_SECONDS = 10;

    private final HikariDataSource dataSource;
    private final SqlSessionTemplate sessions;
    private final WriteRegistry registry;

    private WriteDatabase(HikariDataSource dataSource, SqlSessionTemplate sessions, WriteRegistry registry) {
        this.dataSource = dataSource;
        this.sessions = sessions;
        this.registry = registry;
    }

    /**
     * 打开可写数据源。正式代码只由 {@link WriteDataSourceConfiguration} 以 {@link WriteRegistry#registered()} 调用；
     * 演练与测试可以传入自己的登记表（静态护栏禁止 common.write 以外的正式代码调用）。
     */
    public static WriteDatabase open(WriteDataSourceSettings settings, WriteRegistry registry) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(POOL_NAME);
        cfg.setJdbcUrl(settings.url());
        cfg.setUsername(settings.username());
        cfg.setPassword(settings.password());
        cfg.setMaximumPoolSize(settings.poolSize());
        cfg.setMinimumIdle(1);
        cfg.setReadOnly(false);
        cfg.setAutoCommit(true);
        cfg.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        // 不在构造时连接（构造失败时连接池会把驱动的错误信息写进日志），由下面的检查连接并只报异常类型
        cfg.setInitializationFailTimeout(-1);
        cfg.addDataSourceProperty("useAffectedRows", "false");
        cfg.addDataSourceProperty("allowMultiQueries", "false");
        cfg.addDataSourceProperty("allowLoadLocalInfile", "false");
        cfg.addDataSourceProperty("allowUrlInLocalInfile", "false");
        cfg.addDataSourceProperty("connectTimeout", String.valueOf(CONNECTION_TIMEOUT_MS));

        HikariDataSource ds;
        try {
            ds = new HikariDataSource(cfg);
        } catch (RuntimeException e) {
            throw failure("创建连接池失败", e);
        }
        try {
            verifyConnection(ds, settings.database());
            return new WriteDatabase(ds, new SqlSessionTemplate(sessionFactory(ds, registry)), registry);
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
    }

    /**
     * 安装向导（B22，docs/architecture/31-b22-install-wizard.md 第 5 节）对向导校验过的目标库打开的可写数据源：地址、账号、密码来自向导表单，
     * 不来自 {@code maccms.write.*}；校验与 {@link WriteDataSourceSettings#from} 相同（地址同时作为只读连接池地址，即只能是同一个库；
     * root 需要 {@code allowRoot}），连接池大小 1，使用正式登记表。静态护栏只允许 {@code install.infrastructure} 调用。
     */
    public static WriteDatabase openInstallTarget(String url, String username, String password, boolean allowRoot) {
        WriteDataSourceProperties p = new WriteDataSourceProperties(true, url, username, password, allowRoot, 1);
        return open(WriteDataSourceSettings.from(p, url), WriteRegistry.registered());
    }

    /**
     * 取得写 Mapper：接口必须标 {@link WriteMapper}、不标 MyBatis {@code @Mapper}，且位于 {@code <module>.infrastructure} 下。
     * 被护栏拒绝的语句直接抛 {@link WriteRejectedException}（MyBatis 会把拦截器的异常包两层，这里取出来）。
     */
    public <T> T mapper(Class<T> type) {
        if (!type.isInterface() || !type.isAnnotationPresent(WriteMapper.class)) {
            throw new WriteRejectedException("写 Mapper 必须是标了 @WriteMapper 的接口");
        }
        if (type.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class)) {
            throw new WriteRejectedException("写 Mapper 不能同时标 @Mapper（会被绑定到只读连接池）");
        }
        WriteRegistry.moduleOf(type.getName());
        T target = sessions.getMapper(type);
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                for (Throwable t = cause; t != null && t != t.getCause(); t = t.getCause()) {
                    if (t instanceof WriteRejectedException rejected) {
                        throw rejected;
                    }
                }
                throw cause;
            }
        }));
    }

    public WriteRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static void verifyConnection(HikariDataSource ds, String database) {
        String catalog;
        boolean readOnly;
        try (Connection c = ds.getConnection()) {
            catalog = c.getCatalog();
            readOnly = c.isReadOnly();
        } catch (SQLException | RuntimeException e) {
            throw failure("无法取得连接", e);
        }
        if (readOnly) {
            throw new IllegalStateException("可写数据源初始化失败：连接是只读会话");
        }
        if (!database.equals(catalog)) {
            throw new IllegalStateException("可写数据源初始化失败：连接的当前库与 maccms.write.url 中的库名不一致");
        }
    }

    private static SqlSessionFactory sessionFactory(HikariDataSource ds, WriteRegistry registry) {
        Configuration conf = new Configuration();
        conf.setMapUnderscoreToCamelCase(false);
        conf.setArgNameBasedConstructorAutoMapping(true);
        conf.setDefaultStatementTimeout(STATEMENT_TIMEOUT_SECONDS);
        conf.setCacheEnabled(false);
        conf.setLocalCacheScope(LocalCacheScope.STATEMENT);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setConfiguration(conf);
        bean.setEnvironment(POOL_NAME);
        bean.setPlugins(new WriteGuardInterceptor(registry));
        try {
            Resource[] xml = new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATIONS);
            bean.setMapperLocations(xml);
            return bean.getObject();
        } catch (Exception e) {
            // 写 Mapper XML 的解析错误只涉及资源路径与语句，不含连接信息，保留 cause 便于定位
            throw new IllegalStateException("可写数据源初始化失败：加载写 Mapper XML 失败", e);
        }
    }

    /** 只报异常类型链，不带 cause：驱动与连接池的 message 可能含地址、账号。 */
    private static IllegalStateException failure(String what, Throwable e) {
        return new IllegalStateException("可写数据源初始化失败：" + what + "（检查 MACCMS_WRITE_DB_URL/USERNAME/PASSWORD）："
                + SafeExceptionLog.typeChain(e));
    }
}

package com.maccms.backend.install.infrastructure;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.WriteDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 安装向导到目标库的连接（B22，31 文档第 5 节；APPK-2 S3-20 增加第三种）：
 * <ul>
 * <li>{@link #openReadOnly}：一次性的只读会话（{@code setReadOnly(true)}，MySQL 拒绝任何写语句），连接测试与表结构校验只用它，
 * 只发 {@link InstallDatabaseProbe} 里写死的 SELECT；</li>
 * <li>{@link #openWrite}：经 B07 可写数据源（{@link WriteDatabase#openInstallTarget}），只能执行登记表中 install 的两条 INSERT；</li>
 * <li>{@link #executeInstallSql}：第 5 步执行经 SHA-256 校验的 install.sql（APPK-2 INS-2 ④），会话只在该方法内存在。</li>
 * </ul>
 * 地址里只有校验过的主机、端口、库名和这里固定的参数；账号密码只经驱动属性传入；禁止多语句与本地文件导入。
 * 集成测试可以继承它给地址加上记录语句的驱动拦截器（31 文档第 9 节），正式代码只用这个类本身。
 */
public class InstallConnections {

    private static final Logger LOG = LoggerFactory.getLogger(InstallConnections.class);

    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int SOCKET_TIMEOUT_MS = 30000;

    /** 地址的固定参数（不含凭证；写连接的其余固定参数由 {@link WriteDatabase} 设置）。 */
    protected String parameters() {
        return "characterEncoding=UTF-8&connectTimeout=" + CONNECT_TIMEOUT_MS;
    }

    /** 连接测试：先不带库名连接服务器（同旧版 {@code Db::connect} 不带 database，Index.php:138-141）。 */
    public Connection openServerReadOnly(InstallTarget t) throws SQLException {
        return open("jdbc:mysql://" + t.host() + ":" + t.port() + "/?" + parameters(), t);
    }

    /** 带库名的只读会话。 */
    public Connection openReadOnly(InstallTarget t) throws SQLException {
        return open(t.baseUrl() + "?" + parameters(), t);
    }

    /** 经 B07 可写数据源打开目标库（连接池大小 1，调用方用完关闭）。 */
    public WriteDatabase openWrite(InstallTarget t, boolean allowRoot) {
        return WriteDatabase.openInstallTarget(t.baseUrl() + "?" + parameters(), t.username(), t.password(), allowRoot);
    }

    /** {@link #executeInstallSql} 的结果。 */
    public enum SqlResult {
        OK, CONNECT_FAILED, SQL_FAILED
    }

    /**
     * 第 5 步执行 install.sql（APPK-2 S3-20，技术设计 §6.14 INS-2 ④、INS-4）：在本次调用内打开一个到目标库的普通会话，逐条执行
     * {@code statements}，用后关闭；任何一条失败即停止（同 PHP：已执行的 DDL 不回滚，INS-2 失败后的状态）。
     *
     * <p>{@code statements} 只能是 {@code install.application.InstallSqlFile#statements} 对 SHA-256 与内置常量一致的
     * {@code install.sql} 的切分结果（调用方 {@code InstallWizard} 在调用前校验哈希）——这是 {@code SqlEntryGuardTest} 为本方法登记的唯一
     * 非常量 SQL 入口。连接禁止多语句与本地文件导入，每次只发一条语句；不发 {@code CREATE DATABASE}（库由部署方预建，INS-3）。
     */
    public SqlResult executeInstallSql(InstallTarget t, List<String> statements) {
        Connection c;
        try {
            c = openSession(t.baseUrl() + "?" + parameters(), t);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("安装向导打开建表连接失败：{}", SafeExceptionLog.typeChain(e));
            return SqlResult.CONNECT_FAILED;
        }
        int index = 0;
        try (c; Statement st = c.createStatement()) {
            for (String sql : statements) {
                index++;
                st.execute(sql);
            }
            return SqlResult.OK;
        } catch (SQLException | RuntimeException e) {
            // 只记序号与类型链，不记 SQL 原文
            LOG.warn("安装向导执行 install.sql 第 {} 条失败：{}", index, SafeExceptionLog.typeChain(e));
            return SqlResult.SQL_FAILED;
        }
    }

    private static Connection open(String url, InstallTarget t) throws SQLException {
        Connection c = openSession(url, t);
        try {
            c.setReadOnly(true);
            return c;
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private static Connection openSession(String url, InstallTarget t) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", t.username());
        p.setProperty("password", t.password());
        p.setProperty("allowMultiQueries", "false");
        p.setProperty("allowLoadLocalInfile", "false");
        p.setProperty("allowUrlInLocalInfile", "false");
        p.setProperty("autoDeserialize", "false");
        p.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_MS));
        return DriverManager.getConnection(url, p);
    }
}

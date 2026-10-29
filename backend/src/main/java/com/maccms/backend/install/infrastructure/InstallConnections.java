package com.maccms.backend.install.infrastructure;

import com.maccms.backend.common.write.WriteDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 安装向导到目标库的连接（B22，31 文档第 5 节）。只有两种：
 * <ul>
 * <li>{@link #openReadOnly}：一次性的只读会话（{@code setReadOnly(true)}，MySQL 拒绝任何写语句），连接测试与表结构校验只用它，
 * 只发 {@link InstallDatabaseProbe} 里写死的 SELECT；</li>
 * <li>{@link #openWrite}：经 B07 可写数据源（{@link WriteDatabase#openInstallTarget}），只能执行登记表中 install 的两条 INSERT。</li>
 * </ul>
 * 地址里只有校验过的主机、端口、库名和这里固定的参数；账号密码只经驱动属性传入；禁止多语句与本地文件导入。
 * 集成测试可以继承它给地址加上记录语句的驱动拦截器（31 文档第 9 节），正式代码只用这个类本身。
 */
public class InstallConnections {

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

    private static Connection open(String url, InstallTarget t) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", t.username());
        p.setProperty("password", t.password());
        p.setProperty("allowMultiQueries", "false");
        p.setProperty("allowLoadLocalInfile", "false");
        p.setProperty("allowUrlInLocalInfile", "false");
        p.setProperty("autoDeserialize", "false");
        p.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_MS));
        Connection c = DriverManager.getConnection(url, p);
        try {
            c.setReadOnly(true);
            return c;
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }
}

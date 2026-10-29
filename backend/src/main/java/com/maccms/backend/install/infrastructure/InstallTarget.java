package com.maccms.backend.install.infrastructure;

/**
 * 安装向导要连接的目标库（B22，docs/architecture/31-b22-install-wizard.md 第 5 节）：主机、端口、库名都已按 {@link #HOST}、
 * {@link #DATABASE} 校验（只能出现在 JDBC 地址的固定位置，不能带参数），账号与密码只经驱动属性传入、不进地址。
 */
public record InstallTarget(String host, int port, String database, String username, String password) {

    /** 主机名或 IPv4 地址（与 {@code WriteDataSourceSettings} 接受的地址形式相同）。 */
    public static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    /** 库名（与 {@code WriteDataSourceSettings} 接受的地址形式相同）。 */
    public static final java.util.regex.Pattern DATABASE = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,64}");

    public InstallTarget {
        if (host == null || !HOST.matcher(host).matches() || port < 1 || port > 65535 || database == null
                || !DATABASE.matcher(database).matches() || username == null || password == null) {
            throw new IllegalArgumentException("目标库参数不合法");
        }
    }

    /** {@code jdbc:mysql://主机:端口/库名}（不含参数）。 */
    public String baseUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    /** 不输出账号与密码。 */
    @Override
    public String toString() {
        return "InstallTarget[port=" + port + "]";
    }
}

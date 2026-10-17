package com.maccms.backend.common.write;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可写数据源 {@code maccms.write.*}（B07，docs/architecture/12-write-infrastructure.md 第 2 节）。默认关闭；
 * 打开时地址、账号、密码都必须经 {@code MACCMS_WRITE_DB_*} 单独提供，不沿用只读连接池的配置。校验在
 * {@link WriteDataSourceSettings#from} 中进行，错误只报配置项名称。
 *
 * @param enabled   是否创建可写数据源（{@code MACCMS_WRITE_ENABLED}，默认 false）
 * @param url       JDBC 地址（{@code MACCMS_WRITE_DB_URL}），必须与只读连接池指向同一个库
 * @param username  账号（{@code MACCMS_WRITE_DB_USERNAME}）
 * @param password  密码（{@code MACCMS_WRITE_DB_PASSWORD}）
 * @param allowRoot 是否允许 root（{@code MACCMS_WRITE_DB_ALLOW_ROOT}，仅本机开发）
 * @param poolSize  连接池大小（{@code MACCMS_WRITE_DB_POOL_SIZE}，1～50，默认 4）
 */
@ConfigurationProperties(prefix = "maccms.write")
public record WriteDataSourceProperties(Boolean enabled, String url, String username, String password, Boolean allowRoot,
                                        Integer poolSize) {

    /** 不输出地址、账号与密码。 */
    @Override
    public String toString() {
        return "WriteDataSourceProperties[enabled=" + enabled + "]";
    }
}

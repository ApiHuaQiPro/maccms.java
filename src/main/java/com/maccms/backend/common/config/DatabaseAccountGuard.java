package com.maccms.backend.common.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动时检查数据库账号。新系统只读旧库，应使用只有 SELECT 权限的专用账号；
 * 使用 root 必须显式设置 MACCMS_DB_ALLOW_ROOT=true（仅限本机开发），否则拒绝启动。
 * 连接池另外设置了 read-only（见 application.yml），即使误用高权限账号也不能写库。
 */
@Component
public class DatabaseAccountGuard implements InitializingBean {

    private final String username;
    private final boolean allowRoot;

    public DatabaseAccountGuard(@Value("${spring.datasource.username:}") String username,
                                @Value("${maccms.datasource.allow-root:false}") boolean allowRoot) {
        this.username = username;
        this.allowRoot = allowRoot;
    }

    @Override
    public void afterPropertiesSet() {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("数据库账号未配置：请通过环境变量 MACCMS_DB_USERNAME 提供只读账号");
        }
        if ("root".equalsIgnoreCase(username.trim()) && !allowRoot) {
            throw new IllegalStateException("拒绝使用 root 账号连接数据库：请使用只有 SELECT 权限的专用账号；"
                    + "本机开发确需使用时设置 MACCMS_DB_ALLOW_ROOT=true");
        }
    }
}

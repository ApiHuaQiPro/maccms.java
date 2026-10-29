package com.maccms.backend.install.application;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 安装模式的部署参数（B22，docs/architecture/31-b22-install-wizard.md 第 2.2 节），只来自环境变量；错误信息只含参数名，不回显取值。
 *
 * @param deployConfig 部署配置文件（{@code MACCMS_DEPLOY_CONFIG}，绝对路径，不在旧站目录里）
 * @param allowRoot    向导是否接受 root 账号（{@code MACCMS_INSTALL_ALLOW_ROOT}，默认 false，仅本机开发）
 * @param adminEntry   安装完成后跳转的后台入口（{@code MACCMS_ADMIN_ENTRY}，留空时同旧版 {@code admin.php}）
 */
public record InstallSettings(Path deployConfig, boolean allowRoot, String adminEntry) {

    /** 同 B12 {@code MACCMS_ADMIN_ENTRY} 的写法：根下单段 {@code .php}。 */
    private static final Pattern ENTRY = Pattern.compile("/[A-Za-z0-9_\\-]{1,64}\\.php");

    /**
     * @param deployConfig 原始取值
     * @param siteRoot     旧站根目录（{@code MACCMS_LEGACY_CONFIG_DIR} 的上一级），部署配置文件不能在它里面（网站目录会被 nginx 直接提供）
     */
    public static InstallSettings of(String deployConfig, String allowRoot, String adminEntry, Path siteRoot) {
        if (deployConfig == null || deployConfig.isBlank()) {
            throw new IllegalStateException("安装模式需要 MACCMS_DEPLOY_CONFIG：部署配置文件的绝对路径（向导把数据库连接写在这里）");
        }
        Path p;
        try {
            p = Path.of(deployConfig.strip());
        } catch (RuntimeException e) {
            throw new IllegalStateException("MACCMS_DEPLOY_CONFIG 不是合法路径");
        }
        if (!p.isAbsolute() || p.getFileName() == null || !p.getFileName().toString().endsWith(".properties")) {
            throw new IllegalStateException("MACCMS_DEPLOY_CONFIG 须为以 .properties 结尾的绝对路径");
        }
        p = p.normalize();
        Path dir = p.getParent();
        try {
            Path realDir = dir.toRealPath();
            if (siteRoot != null && realDir.startsWith(siteRoot.toRealPath())) {
                throw new IllegalStateException("MACCMS_DEPLOY_CONFIG 不能放在旧站目录里（网站目录可能被直接访问，文件里有数据库密码）");
            }
            p = realDir.resolve(p.getFileName());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("MACCMS_DEPLOY_CONFIG 所在的目录不存在");
        }
        String entry = adminEntry == null ? "" : adminEntry.strip();
        if (!entry.isEmpty() && !ENTRY.matcher(entry).matches()) {
            throw new IllegalStateException("MACCMS_ADMIN_ENTRY 须为形如 /_admin.php 的单段 .php 路径");
        }
        return new InstallSettings(p, "true".equalsIgnoreCase(allowRoot == null ? "" : allowRoot.strip()), entry);
    }

    /** 安装完成后的跳转地址（旧 Index.php:269-271：安装目录 + {@code admin.php}）。 */
    public String adminUrl() {
        return adminEntry.isEmpty() ? "/admin.php" : adminEntry;
    }
}

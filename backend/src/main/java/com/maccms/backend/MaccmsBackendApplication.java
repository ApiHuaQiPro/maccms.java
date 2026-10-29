package com.maccms.backend;

import com.maccms.backend.install.InstallApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MacCMS 重构的 Java 模块化单体入口。模块按包划分：common（公共支持）、content（内容只读，首批）。
 * 规划中的其他模块见 docs/architecture/01-batch1-content-read-plan.md，本批不建空包。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MaccmsBackendApplication {

    public static void main(String[] args) {
        // B22：MACCMS_INSTALL_MODE=true 时只启动安装向导（docs/architecture/31-b22-install-wizard.md 第 2 节）
        if (InstallApplication.requested(System.getenv())) {
            InstallApplication.main(args);
            return;
        }
        SpringApplication.run(MaccmsBackendApplication.class, args);
    }
}

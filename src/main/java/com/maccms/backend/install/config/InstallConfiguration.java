package com.maccms.backend.install.config;

import com.maccms.backend.install.application.InstallEnvironment;
import com.maccms.backend.install.application.InstallSettings;
import com.maccms.backend.install.application.InstallWizard;
import com.maccms.backend.install.infrastructure.DeployConfigFile;
import com.maccms.backend.install.infrastructure.InstallConnections;
import com.maccms.backend.install.web.InstallEndpoint;
import com.maccms.backend.install.web.InstallResponses;
import com.maccms.backend.install.web.jte.StaticTemplates;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 安装模式的装配（B22，31 文档第 2 节）。<b>故意不标 {@code @Configuration}</b>：只由 {@code InstallApplication} 显式引入，正常启动的
 * {@code MaccmsBackendApplication} 扫描不到它，正常模式下没有任何安装向导的组件与路由。
 *
 * <p>启动时检查：必须配置旧配置目录（{@code MACCMS_LEGACY_CONFIG_DIR}，站点配置与安装锁都在旧站里）与部署配置文件
 * （{@code MACCMS_DEPLOY_CONFIG}），否则启动失败（只报参数名）。
 */
public class InstallConfiguration {

    @Bean
    InstallSettings installSettings(Environment env, LegacySiteFiles files) {
        if (!files.isConfigured()) {
            throw new IllegalStateException("安装模式需要 MACCMS_LEGACY_CONFIG_DIR：旧站 application 目录（站点配置与安装锁写在这里）");
        }
        Path siteRoot;
        try {
            siteRoot = Path.of(env.getProperty("maccms.legacy-config.dir", "").strip()).toRealPath().getParent();
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("MACCMS_LEGACY_CONFIG_DIR 不可用");
        }
        return InstallSettings.of(env.getProperty("MACCMS_DEPLOY_CONFIG"), env.getProperty("MACCMS_INSTALL_ALLOW_ROOT"),
                env.getProperty("MACCMS_ADMIN_ENTRY"), siteRoot);
    }

    @Bean
    DeployConfigFile deployConfigFile(InstallSettings settings) {
        return new DeployConfigFile(settings.deployConfig());
    }

    /** 集成测试可以先注册自己的 {@link InstallConnections}（加上记录语句的驱动拦截器），正式启动只用这个默认实现。 */
    @Bean
    InstallConnections installConnections(ObjectProvider<InstallConnectionsOverride> override) {
        InstallConnectionsOverride o = override.getIfAvailable();
        return o == null ? new InstallConnections() : o.connections();
    }

    @Bean
    InstallWizard installWizard(InstallSettings settings, DeployConfigFile deploy, InstallConnections connections,
                                LegacyConfigService config, LegacySiteFiles files) {
        return new InstallWizard(settings, deploy, connections, config, files);
    }

    @Bean
    InstallEndpoint installEndpoint(InstallWizard wizard, LegacySiteFiles files, DeployConfigFile deploy) {
        InstallResponses responses = new InstallResponses(new StaticTemplates(), "/install-assets");
        return new InstallEndpoint(wizard, new InstallEnvironment(files, deploy), responses, "/static", "/install-assets");
    }

    @Bean
    RouterFunction<ServerResponse> installRoutes(InstallEndpoint endpoint) {
        return RouterFunctions.route()
                .route(RequestPredicates.path("/install.php/**").or(RequestPredicates.path("/install.php"))
                        .and(r -> InstallEndpoint.PATHS.matcher(r.path()).matches()), endpoint::handle)
                .build();
    }

    /** 测试替换连接工厂用的标记类型。 */
    public interface InstallConnectionsOverride {
        InstallConnections connections();
    }
}

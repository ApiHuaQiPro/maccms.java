package com.maccms.backend.install;

import com.maccms.backend.install.config.InstallConfiguration;
import com.maccms.backend.legacyconfig.application.LegacyConfigConfiguration;
import java.util.Map;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/**
 * 安装模式（B22，docs/architecture/31-b22-install-wizard.md 第 2 节）：旧版 {@code install.php} 是只绑定 install 模块的单独入口
 * （{@code BIND_MODULE=install}）；新版对应地以 {@code MACCMS_INSTALL_MODE=true} 启动一个<b>只有安装向导</b>的应用：
 * <ul>
 * <li>不需要、也不创建数据库连接池（数据库连接由向导第三、四步填写），不扫描其他模块，没有前台、后台、接口路由；</li>
 * <li>只引入旧配置层（站点配置写回与安装锁）与 {@link InstallConfiguration}；</li>
 * <li>安装完成后由部署方去掉 {@code MACCMS_INSTALL_MODE} 重启为正常模式，正常模式读向导写下的部署配置文件。</li>
 * </ul>
 * 本类与 {@link InstallConfiguration} 都<b>不带</b> {@code @Configuration}/{@code @SpringBootConfiguration} 等组件注解：只作为
 * {@link #builder()} 的启动类处理（{@code @Import} 与自动配置照常生效），不做组件扫描；正常启动的 {@code MaccmsBackendApplication}
 * 扫描 {@code com.maccms.backend} 时也不会把它们当成配置类（{@code InstallModeIsolationTest} 锁定）。
 */
@EnableAutoConfiguration(excludeName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration",
    "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
    "org.mybatis.spring.boot.autoconfigure.MybatisLanguageDriverAutoConfiguration"})
@Import({LegacyConfigConfiguration.class, InstallConfiguration.class})
public class InstallApplication {

    /** 启动安装模式的环境变量。 */
    public static final String MODE_ENV = "MACCMS_INSTALL_MODE";

    /** 是否要求以安装模式启动（{@code MACCMS_INSTALL_MODE=true}，不区分大小写）。 */
    public static boolean requested(Map<String, String> env) {
        String v = env.get(MODE_ENV);
        return v != null && v.strip().equalsIgnoreCase("true");
    }

    /** 安装模式的应用（测试也用它构造）。 */
    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(InstallApplication.class)
                .properties(Map.of("spring.application.name", "maccms-install"));
    }

    public static void main(String[] args) {
        builder().run(args);
    }
}

package com.maccms.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.admin.web.AdminController;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 后台的启用条件（21 文档第 7 节）：默认关闭时没有任何后台组件；启用时可写数据源必须打开、入口与对外地址必须合法，否则启动失败，
 * 信息只有配置项名称。数据源地址不可达（不建立任何连接）。
 */
class AdminStartupTest {

    private static ConfigurableApplicationContext start(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "--spring.datasource.username=synthetic_ro", "--spring.datasource.password=synthetic"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private static Throwable root(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void disabledByDefault() {
        try (ConfigurableApplicationContext app = start()) {
            assertThat(app.getBeanNamesForType(AdminController.class)).isEmpty();
            assertThat(app.getEnvironment().getProperty("maccms.admin.enabled")).isEqualTo("false");
        }
    }

    @Test
    void enabledRequiresTheWriteDataSource() {
        assertThatThrownBy(() -> start("--maccms.admin.enabled=true", "--maccms.admin.entry=/_admin.php",
                "--maccms.admin.base-url=http://cms.local.test/"))
                .satisfies(e -> assertThat(root(e)).hasMessageContaining("可写数据源未打开")
                        .hasMessageNotContaining("synthetic"));
    }

    @Test
    void enabledRequiresAValidEntryAndBaseUrl() {
        assertThatThrownBy(() -> start("--maccms.admin.enabled=true", "--maccms.admin.entry=/admin.php"))
                .satisfies(e -> assertThat(root(e)).hasMessageContaining("maccms.admin.entry")
                        .hasMessageContaining("maccms.admin.base-url"));
        assertThatThrownBy(() -> start("--maccms.admin.enabled=yes"))
                .satisfies(e -> assertThat(root(e)).hasMessage("maccms.admin.enabled 只能是 true 或 false"));
    }
}

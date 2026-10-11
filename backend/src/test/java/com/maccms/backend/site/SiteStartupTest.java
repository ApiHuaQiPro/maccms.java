package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.site.application.SiteSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 用真实的 SpringApplication 启动整个应用（含 Boot 的启动失败分析与日志），确认：
 * 默认不启用 site；启用后配置缺失或非法则启动失败，启动日志只出现配置项名称，不出现取值。
 * 数据源用不可达的合成地址，启动过程不连接数据库。
 */
@ExtendWith(OutputCaptureExtension.class)
class SiteStartupTest {

    static final String SECRET = "SYNTHETIC-SECRET-a83f";

    private static String[] args(String... site) {
        List<String> a = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "--spring.datasource.username=synthetic_ro",
                "--spring.datasource.password=synthetic",
                "--spring.main.banner-mode=off"));
        for (String s : site) {
            a.add("--" + s);
        }
        return a.toArray(String[]::new);
    }

    private static SpringApplication app() {
        SpringApplication app = new SpringApplication(MaccmsBackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app;
    }

    @Test
    void siteIsDisabledByDefaultAndInvalidSiteValuesAreIgnored() {
        try (ConfigurableApplicationContext ctx = app().run(args("maccms.site.base-url=javascript:" + SECRET))) {
            assertThat(ctx.getBeanNamesForType(SiteSettings.class)).isEmpty();
        }
    }

    @Test
    void enabledWithoutRequiredValuesFailsStartup(CapturedOutput output) {
        assertThatThrownBy(() -> app().run(args("maccms.site.enabled=true")).close())
                .hasStackTraceContaining("maccms.site.name")
                .hasStackTraceContaining("maccms.site.base-url");
        assertThat(output.getAll()).contains("maccms.site.base-url");
    }

    @Test
    void enabledWithInvalidValuesFailsStartupWithoutEchoingThem(CapturedOutput output) {
        assertThatThrownBy(() -> app().run(args("maccms.site.enabled=true",
                "maccms.site.name=合成站" + SECRET + "\u0007",
                "maccms.site.base-url=https://u:" + SECRET + "@maccms.local.test/?k=" + SECRET,
                "maccms.site.logo-url=javascript:" + SECRET,
                "maccms.site.status=" + SECRET,
                "maccms.site.canonical-redirect=true")).close())
                .hasStackTraceContaining("maccms.site.base-url")
                .hasStackTraceContaining("maccms.site.canonical-redirect")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(SECRET));
        assertThat(output.getAll()).contains("maccms.site.base-url").doesNotContain(SECRET);
    }

    @Test
    void invalidEnabledFlagFailsStartup(CapturedOutput output) {
        assertThatThrownBy(() -> app().run(args("maccms.site.enabled=" + SECRET)).close())
                .hasStackTraceContaining("maccms.site.enabled")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(SECRET));
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}

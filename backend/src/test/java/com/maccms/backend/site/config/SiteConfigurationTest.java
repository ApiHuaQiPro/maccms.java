package com.maccms.backend.site.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.SiteHtmlRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** maccms.site.enabled 的解析，以及启用时站点配置的启动校验（在容器内）。 */
class SiteConfigurationTest {

    static final String SECRET = "SYNTHETIC-SECRET-7d42";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(SiteConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "false", "FALSE", " false "})
    void disabledValues(String v) {
        assertThat(SiteEnabledCondition.isEnabled(v)).isFalse();
    }

    @Test
    void unsetIsDisabled() {
        assertThat(SiteEnabledCondition.isEnabled(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " true "})
    void enabledValues(String v) {
        assertThat(SiteEnabledCondition.isEnabled(v)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "on", "enabled", SECRET})
    void otherValuesFailWithoutEcho(String v) {
        assertThatThrownBy(() -> SiteEnabledCondition.isEnabled(v))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.enabled")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void disabledByDefaultRegistersNothingAndSkipsValidation() {
        runner.withPropertyValues("maccms.site.base-url=javascript:alert(1)").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SiteSettings.class);
            assertThat(ctx).doesNotHaveBean(SiteHtmlRenderer.class);
        });
    }

    @Test
    void enabledWithValidConfigurationStarts() {
        runner.withPropertyValues("maccms.site.enabled=true", "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SiteSettings.class).baseUrl().href()).isEqualTo("http://maccms.local.test/");
            assertThat(ctx).hasSingleBean(SiteHtmlRenderer.class);
        });
    }

    @Test
    void enabledWithMissingConfigurationFails() {
        runner.withPropertyValues("maccms.site.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("maccms.site.name").hasMessageContaining("maccms.site.base-url");
        });
    }

    @Test
    void enabledWithInvalidConfigurationFailsWithoutEchoingValues() {
        runner.withPropertyValues("maccms.site.enabled=true", "maccms.site.name=合成站",
                "maccms.site.base-url=http://user:" + SECRET + "@maccms.local.test/",
                "maccms.site.logo-url=javascript:" + SECRET).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("maccms.site.base-url").hasMessageContaining("maccms.site.logo-url");
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("maccms.site.base-url");
            assertThat(stackTrace(ctx.getStartupFailure())).doesNotContain(SECRET);
        });
    }

    @Test
    void invalidEnabledValueFailsStartup() {
        runner.withPropertyValues("maccms.site.enabled=" + SECRET).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(stackTrace(ctx.getStartupFailure())).contains("maccms.site.enabled").doesNotContain(SECRET);
        });
    }

    static String stackTrace(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}

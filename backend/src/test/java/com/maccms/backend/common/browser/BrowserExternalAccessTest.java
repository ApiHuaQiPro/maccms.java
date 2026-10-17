package com.maccms.backend.common.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 浏览器侧外部地址放行参数（B02）：默认 W1/W2 打开、W3 关闭，W1/W2 可关闭；W3 在 B24 之前只接受 false；不合法时启动失败、不回显取值。 */
class BrowserExternalAccessTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BrowserExternalConfiguration.class);

    @Test
    void defaultsToW1W2OpenAndW3Closed() {
        assertThat(BrowserExternalAccess.DEFAULT).isEqualTo(new BrowserExternalAccess(true, true, false));
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties(null, null, null)))
                .isEqualTo(BrowserExternalAccess.DEFAULT);
        assertThat(BrowserExternalAccess.from(null)).isEqualTo(BrowserExternalAccess.DEFAULT);
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties("", "  ", "")))
                .isEqualTo(BrowserExternalAccess.DEFAULT);
        runner.run(ctx -> assertThat(ctx.getBean(BrowserExternalAccess.class)).isEqualTo(BrowserExternalAccess.DEFAULT));
    }

    @Test
    void closedStateIsKept() {
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties(" false ", "FALSE", "false")))
                .isEqualTo(BrowserExternalAccess.ALL_CLOSED);
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties(null, "false", null)))
                .isEqualTo(new BrowserExternalAccess(true, false, false));
        runner.withPropertyValues("maccms.browser-external.w2=false")
                .run(ctx -> assertThat(ctx.getBean(BrowserExternalAccess.class).w2()).isFalse());
    }

    @Test
    void w1AndW2AcceptTrue() {
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties("TRUE", " true ", null)))
                .isEqualTo(BrowserExternalAccess.DEFAULT);
        runner.withPropertyValues("maccms.browser-external.w1=true", "maccms.browser-external.w2=TRUE")
                .run(ctx -> assertThat(ctx.getBean(BrowserExternalAccess.class)).isEqualTo(BrowserExternalAccess.DEFAULT));
    }

    @Test
    void w3OnlyAcceptsFalseUntilB24() {
        assertThatThrownBy(() -> BrowserExternalAccess.from(new BrowserExternalProperties(null, null, "true")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maccms.browser-external.w3")
                .hasMessageContaining("B24");
        runner.withPropertyValues("maccms.browser-external.w3=true").run(ctx -> assertThat(ctx).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "enabled", "test-only-not-a-secret"})
    void invalidValuesFailWithoutEchoingThem(String value) {
        assertThatThrownBy(() -> BrowserExternalAccess.from(new BrowserExternalProperties(value, value, value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.browser-external.w1")
                .hasMessageContaining("maccms.browser-external.w2")
                .hasMessageContaining("maccms.browser-external.w3")
                .hasMessageNotContaining(value);
    }

    @Test
    void applicationYmlBindsEnvironmentVariablesWithDefaults() throws Exception {
        String yml = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        assertThat(yml).contains("w1: ${MACCMS_BROWSER_EXTERNAL_W1:true}", "w2: ${MACCMS_BROWSER_EXTERNAL_W2:true}",
                "w3: ${MACCMS_BROWSER_EXTERNAL_W3:false}");
        // 不是站点配置项：不读写旧 PHP 配置
        assertThat(Files.readString(Path.of("..", "application", "extra", "maccms.php")))
                .doesNotContain("browser-external", "browser_external");
    }
}

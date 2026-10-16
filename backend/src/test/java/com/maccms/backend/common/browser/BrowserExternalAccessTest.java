package com.maccms.backend.common.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 浏览器侧外部地址放行参数（B02）：默认全部关闭；W1/W3 在实现批次之前只接受 false；不合法时启动失败、不回显取值。 */
class BrowserExternalAccessTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BrowserExternalConfiguration.class);

    @Test
    void defaultsToAllClosed() {
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties(null, null, null)))
                .isEqualTo(BrowserExternalAccess.ALL_CLOSED);
        assertThat(BrowserExternalAccess.from(null)).isEqualTo(BrowserExternalAccess.ALL_CLOSED);
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties("", " false ", "FALSE")))
                .isEqualTo(BrowserExternalAccess.ALL_CLOSED);
        runner.run(ctx -> assertThat(ctx.getBean(BrowserExternalAccess.class)).isEqualTo(BrowserExternalAccess.ALL_CLOSED));
    }

    @Test
    void w2CanBeOpened() {
        assertThat(BrowserExternalAccess.from(new BrowserExternalProperties(null, "true", null)))
                .isEqualTo(new BrowserExternalAccess(false, true, false));
        runner.withPropertyValues("maccms.browser-external.w2=TRUE")
                .run(ctx -> assertThat(ctx.getBean(BrowserExternalAccess.class).w2()).isTrue());
    }

    @Test
    void w1AndW3OnlyAcceptFalseUntilTheirBatches() {
        assertThatThrownBy(() -> BrowserExternalAccess.from(new BrowserExternalProperties("true", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maccms.browser-external.w1")
                .hasMessageContaining("B05");
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
    void applicationYmlBindsEnvironmentVariablesWithClosedDefaults() throws Exception {
        String yml = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        assertThat(yml).contains("w1: ${MACCMS_BROWSER_EXTERNAL_W1:false}", "w2: ${MACCMS_BROWSER_EXTERNAL_W2:false}",
                "w3: ${MACCMS_BROWSER_EXTERNAL_W3:false}");
        // 不是站点配置项：不读写旧 PHP 配置
        assertThat(Files.readString(Path.of("..", "application", "extra", "maccms.php")))
                .doesNotContain("browser-external", "browser_external");
    }
}

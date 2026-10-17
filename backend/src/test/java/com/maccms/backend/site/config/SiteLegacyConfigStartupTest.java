package com.maccms.backend.site.config;

import static com.maccms.backend.site.config.SiteConfigurationTest.stackTrace;
import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SiteSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * site 装配时读取旧配置（B02，11 文档第 4.4 节）：启动时读一次、按字段合并；读不到、写坏、取值或 URL 形态不受支持时启动失败，
 * 错误信息只含键名与原因，不含取值或文件内容；运行中改文件不影响已启动的站点设置（热加载在 B15）。
 */
class SiteLegacyConfigStartupTest {

    static final String SECRET = "SYNTHETIC-SECRET-0e3a";

    @TempDir
    Path dir;

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(SiteConfiguration.class);

    private WebApplicationContextRunner site(String... extra) {
        return runner.withPropertyValues("maccms.site.enabled=true", "maccms.site.base-url=https://www.maccms.test/",
                "maccms.legacy-config.dir=" + dir).withPropertyValues(extra);
    }

    private void write(String php) throws Exception {
        Files.createDirectories(dir.resolve("extra"));
        Files.writeString(dir.resolve("extra/maccms.php"), php, StandardCharsets.UTF_8);
    }

    private static String valid(String siteName, String status, String routeStatus) {
        return "<?php\nreturn array (\n  'site' => array ('site_name' => '" + siteName + "', 'install_dir' => '/', "
                + "'site_status' => '" + status + "', 'site_close_tip' => '维护'),\n"
                + "  'app' => array ('pathinfo_depr' => '/'),\n"
                + "  'rewrite' => array ('status' => '1', 'suffix_hide' => '0', 'route_status' => '" + routeStatus + "'),\n"
                + ");\n";
    }

    @Test
    void readsLegacyConfigOnceAtStartup() throws Exception {
        write(valid("旧站", "0", "0"));
        site().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SiteSettings settings = ctx.getBean(SiteSettings.class);
            assertThat(settings.name()).isEqualTo("旧站");
            assertThat(settings.closed()).isTrue();
            assertThat(settings.closedMessage()).isEqualTo("维护");
            assertThat(ctx.getBean(LegacyUrlBuilder.class)).isNotNull();
            assertThat(ctx).hasSingleBean(ImageUrlNormalizer.class);

            // 运行中改旧配置：已启动的站点设置不变（改了要重启）
            write(valid("改过的站", "1", "0"));
            assertThat(ctx.getBean(SiteSettings.class)).isSameAs(settings);
            assertThat(ctx.getBean(SiteSettings.class).name()).isEqualTo("旧站");
        });
    }

    @Test
    void environmentOverridesLegacyFieldByField() throws Exception {
        write(valid("旧站", "0", "0"));
        site("maccms.site.name=环境站", "maccms.site.status=open").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SiteSettings.class).name()).isEqualTo("环境站");
            assertThat(ctx.getBean(SiteSettings.class).closed()).isFalse();
            assertThat(ctx.getBean(SiteSettings.class).closedMessage()).isEqualTo("维护");
        });
    }

    @Test
    void baseUrlIsStillRequiredFromEnvironment() throws Exception {
        write(valid("旧站", "1", "0"));
        runner.withPropertyValues("maccms.site.enabled=true", "maccms.legacy-config.dir=" + dir).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("maccms.site.base-url");
        });
    }

    @Test
    void unsupportedUrlShapeFailsStartupWithoutEchoingValues() throws Exception {
        write(valid(SECRET, "1", SECRET));
        site().run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("rewrite.route_status");
            assertThat(stackTrace(ctx.getStartupFailure())).doesNotContain(SECRET);
        });
    }

    @Test
    void invalidLegacyValueFailsValidationNamingItsSource() throws Exception {
        write(valid("坏\u0001站名" + SECRET, "1", "0"));
        site().run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("maccms.site.name")
                    .hasMessageContaining("maccms.site.name←site.site_name");
            assertThat(stackTrace(ctx.getStartupFailure())).doesNotContain(SECRET);
        });
    }

    @Test
    void brokenOrMissingLegacyFileFailsStartupWithoutContent() throws Exception {
        write("<?php return array ('site' => array ('site_name' => '" + SECRET + "', 'x' => foo()));");
        site().run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("extra/maccms.php")
                    .hasMessageContaining("UNSUPPORTED_CONSTRUCT");
            assertThat(stackTrace(ctx.getStartupFailure())).doesNotContain(SECRET);
        });
        Files.delete(dir.resolve("extra/maccms.php"));
        site().run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("extra/maccms.php")
                    .hasMessageContaining("NOT_FOUND");
        });
    }

    @Test
    void legacyConfigIsIgnoredWhileSiteIsDisabled() throws Exception {
        write("not php " + SECRET);
        runner.withPropertyValues("maccms.legacy-config.dir=" + dir).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SiteSettings.class);
        });
    }
}

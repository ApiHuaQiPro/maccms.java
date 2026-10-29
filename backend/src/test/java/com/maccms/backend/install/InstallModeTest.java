package com.maccms.backend.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.install.application.InstallSettings;
import com.maccms.backend.install.config.InstallConfiguration;
import com.maccms.backend.install.web.InstallEndpoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 安装模式本身（31 文档第 2、4、7 节）：不需要数据库就能启动、只有安装向导的路由；正常模式扫描不到它；安装锁；环境检测不通过时不能进入第三步；
 * 部署参数的校验；正常模式读部署配置文件（环境变量优先）。
 */
class InstallModeTest {

    @Test
    void startsWithoutAnyDatabaseAndServesOnlyTheWizard() throws Exception {
        InstallGolden.Site site = InstallGolden.Site.create();
        try (ConfigurableApplicationContext app = InstallGolden.start(site, false, null, null)) {
            assertThat(app.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(app.getBeanNamesForType(com.maccms.backend.common.write.WriteDatabase.class)).isEmpty();
            MockMvc mvc = InstallGolden.mvc(app);
            for (String path : java.util.List.of("/install.php", "/install.php/", "/install.php/index", "/install.php/index/index",
                    "/install.php/index/index.html")) {
                MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse();
                assertThat(r.getStatus()).as(path).isEqualTo(200);
                assertThat(r.getHeader("Content-Security-Policy")).as(path).contains("script-src 'self'")
                        .doesNotContain("unsafe-inline' 'self'; script");
                assertThat(r.getHeader("Cache-Control")).contains("no-store");
            }
            for (String path : java.util.List.of("/", "/index.php", "/install.php/foo", "/_admin.php", "/api.php/provide/vod")) {
                assertThat(mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse().getStatus()).as(path)
                        .isEqualTo(404);
            }
            // 页面引用的旧样式与新脚本都由本应用提供
            for (String asset : java.util.List.of("/static/css/install.css", "/static/layui/layui.js", "/static/css/admin_style.css",
                    "/install-assets/index.js", "/install-assets/step3.js", "/install-assets/jump.js")) {
                assertThat(mvc.perform(MockMvcRequestBuilders.get(asset)).andReturn().getResponse().getStatus()).as(asset)
                        .isEqualTo(200);
            }
        } finally {
            site.delete();
        }
    }

    @Test
    void lockRejectsEveryRequestAndFailedEnvironmentBlocksStep3() throws Exception {
        InstallGolden.Site site = InstallGolden.Site.create();
        try (ConfigurableApplicationContext app = InstallGolden.start(site, false, null, null)) {
            MockMvc mvc = InstallGolden.mvc(app);
            MockHttpSession s = new MockHttpSession();
            // 环境检测不通过（目录不存在）：第三、四步拒绝，回到首页后清除
            Files.delete(site.application().resolve("data/update"));
            String step2 = mvc.perform(MockMvcRequestBuilders.get("/install.php?step=2").session(s)).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(step2).contains("<tr class=\"no\">", "./application/data/update", "不存在");
            assertThat(mvc.perform(MockMvcRequestBuilders.get("/install.php?step=3").session(s)).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8)).contains("环境检测未通过，不能进行下一步操作！");
            assertThat(mvc.perform(MockMvcRequestBuilders.post("/install.php?step=4").session(s)
                    .header("X-Requested-With", "XMLHttpRequest")).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":0", "环境检测未通过");
            assertThat(mvc.perform(MockMvcRequestBuilders.post("/install.php?step=5").session(s)
                    .header("X-Requested-With", "XMLHttpRequest")).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8)).contains("初始失败！");
            mvc.perform(MockMvcRequestBuilders.get("/install.php").session(s));
            assertThat(mvc.perform(MockMvcRequestBuilders.get("/install.php?step=3").session(s)).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8)).contains("name=\"__token__\"");

            // 安装锁：任何入口只输出旧版的提示
            Files.writeString(site.lock(), "2026-01-01 00:00:00");
            for (String q : java.util.List.of("", "?step=2", "?step=3", "?step=5")) {
                MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.post("/install.php" + q).session(s)).andReturn()
                        .getResponse();
                assertThat(r.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(
                        "如需重新安装请删除【To re install, please remove】 >>> /application/data/install/install.lock");
            }
        } finally {
            site.delete();
        }
    }

    @Test
    void normalModeNeverRegistersTheInstaller() {
        // 安装模式的两个装配类不带组件注解：正常模式的组件扫描找不到 install 包里的任何候选
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(true);
        assertThat(scan.findCandidateComponents("com.maccms.backend.install")).isEmpty();
        assertThat(InstallApplication.class.isAnnotationPresent(Configuration.class)).isFalse();
        assertThat(InstallConfiguration.class.isAnnotationPresent(Configuration.class)).isFalse();
        assertThat(InstallApplication.requested(Map.of("MACCMS_INSTALL_MODE", "true"))).isTrue();
        assertThat(InstallApplication.requested(Map.of("MACCMS_INSTALL_MODE", "1"))).isFalse();
        assertThat(InstallApplication.requested(Map.of())).isFalse();
        assertThat(InstallEndpoint.PATHS.matcher("/install.php/index/index.html").matches()).isTrue();
        assertThat(InstallEndpoint.PATHS.matcher("/install.php/admin/index").matches()).isFalse();
    }

    @Test
    void settingsAreValidated(@TempDir Path dir) throws Exception {
        Path site = Files.createDirectories(dir.resolve("site"));
        Path deploy = Files.createDirectories(dir.resolve("deploy"));
        assertThatThrownBy(() -> InstallSettings.of("", null, null, site)).hasMessageContaining("MACCMS_DEPLOY_CONFIG");
        assertThatThrownBy(() -> InstallSettings.of("relative.properties", null, null, site)).hasMessageContaining("绝对路径");
        assertThatThrownBy(() -> InstallSettings.of(deploy.resolve("x.env").toString(), null, null, site))
                .hasMessageContaining(".properties");
        assertThatThrownBy(() -> InstallSettings.of(site.resolve("x.properties").toString(), null, null, site))
                .hasMessageContaining("旧站目录");
        assertThatThrownBy(() -> InstallSettings.of(dir.resolve("none/x.properties").toString(), null, null, site))
                .hasMessageContaining("目录不存在");
        assertThatThrownBy(() -> InstallSettings.of(deploy.resolve("x.properties").toString(), null, "/a/b.php", site))
                .hasMessageContaining("MACCMS_ADMIN_ENTRY");
        InstallSettings ok = InstallSettings.of(deploy.resolve("x.properties").toString(), "TRUE", "", site);
        assertThat(ok.allowRoot()).isTrue();
        assertThat(ok.adminUrl()).isEqualTo("/admin.php");
        assertThat(InstallSettings.of(deploy.resolve("x.properties").toString(), null, "/_admin.php", site).adminUrl())
                .isEqualTo("/_admin.php");
    }

    /** 正常模式经 spring.config.import 读部署配置文件：未设置时不读；设置后其中的键可用于占位符，同名环境变量（这里用更高优先级的参数）优先。 */
    @Test
    void normalModeImportsTheDeployConfig(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("maccms-deploy.properties");
        Files.writeString(f, "MACCMS_DB_URL=jdbc:mysql://127.0.0.1:3306/maccms_it_x?characterEncoding=UTF-8\n"
                + "MACCMS_DB_USERNAME=test_only_user\n");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .properties("MACCMS_DEPLOY_CONFIG=" + f).run()) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:mysql://127.0.0.1:3306/maccms_it_x?characterEncoding=UTF-8");
        }
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .properties("MACCMS_DEPLOY_CONFIG=" + f).run("--MACCMS_DB_USERNAME=from_env")) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.username")).isEqualTo("from_env");
        }
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).run()) {
            assertThat(ctx.getEnvironment().containsProperty("MACCMS_DB_USERNAME")).isFalse();
        }
    }

    /** 只用来加载 application.yml 的空配置（不扫描、不自动配置）。 */
    static class Empty {
    }
}

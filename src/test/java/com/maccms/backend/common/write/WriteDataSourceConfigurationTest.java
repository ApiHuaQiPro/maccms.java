package com.maccms.backend.common.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 可写数据源的开关与设置校验（12 文档第 2 节）：默认不创建；打开时必须单独提供地址与凭证；错误信息与日志不回显地址、账号、密码。
 * 真实 MySQL 上的打开、拒绝与只读池不受影响见 WriteInfrastructureMySqlIT。
 */
@ExtendWith(OutputCaptureExtension.class)
class WriteDataSourceConfigurationTest {

    private static final String READ_URL = "jdbc:mysql://127.0.0.1:3306/maccms_unit?characterEncoding=UTF-8";
    private static final String USER_MARKER = "wr-user-MARKER7";
    private static final String PASS_MARKER = "wr-pass-MARKER7";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WriteDataSourceConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + READ_URL);

    @Test
    void notCreatedByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(WriteDatabase.class);
            assertThat(ctx.getBean(WriteDataSourceProperties.class).enabled()).isNull();
        });
        runner.withPropertyValues("maccms.write.enabled=false", "maccms.write.url=" + READ_URL,
                "maccms.write.username=" + USER_MARKER, "maccms.write.password=" + PASS_MARKER)
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(WriteDatabase.class));
    }

    @Test
    void enabledWithoutOwnCredentialsFailsWithoutEcho(CapturedOutput out) {
        for (List<String> props : List.of(
                List.of("maccms.write.url=", "maccms.write.username=" + USER_MARKER, "maccms.write.password=" + PASS_MARKER),
                List.of("maccms.write.url=" + READ_URL, "maccms.write.username=", "maccms.write.password=" + PASS_MARKER),
                List.of("maccms.write.url=" + READ_URL, "maccms.write.username=" + USER_MARKER, "maccms.write.password="))) {
            List<String> all = new ArrayList<>(props);
            all.add("maccms.write.enabled=true");
            runner.withPropertyValues(all.toArray(String[]::new)).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(chain(ctx.getStartupFailure())).contains("maccms.write.", "MACCMS_WRITE_DB_", "未设置")
                        .doesNotContain(USER_MARKER).doesNotContain(PASS_MARKER);
            });
        }
        assertThat(out.getAll()).doesNotContain(USER_MARKER).doesNotContain(PASS_MARKER);
    }

    /** 端口 1 上没有 MySQL：连接失败只报异常类型。 */
    @Test
    void connectionFailureDoesNotEchoCredentials(CapturedOutput out) {
        runner.withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:1/maccms_unit",
                "maccms.write.enabled=true", "maccms.write.url=jdbc:mysql://127.0.0.1:1/maccms_unit",
                "maccms.write.username=" + USER_MARKER, "maccms.write.password=" + PASS_MARKER).run(ctx -> {
                    assertThat(ctx).hasFailed();
                    String chain = chain(ctx.getStartupFailure());
                    assertThat(chain).contains("可写数据源初始化失败", "MACCMS_WRITE_DB_")
                            .doesNotContain(USER_MARKER).doesNotContain(PASS_MARKER).doesNotContain("127.0.0.1:1");
                });
        assertThat(out.getAll()).doesNotContain(USER_MARKER).doesNotContain(PASS_MARKER);
    }

    @Test
    void settingsValidation() {
        WriteDataSourceSettings ok = WriteDataSourceSettings.from(props(READ_URL, "maccms_rw", PASS_MARKER, null, null),
                READ_URL);
        assertThat(ok.database()).isEqualTo("maccms_unit");
        assertThat(ok.poolSize()).isEqualTo(WriteDataSourceSettings.DEFAULT_POOL_SIZE);
        assertThat(ok.toString()).doesNotContain(PASS_MARKER).doesNotContain("maccms_rw").doesNotContain("127.0.0.1");
        assertThat(props(READ_URL, USER_MARKER, PASS_MARKER, true, 4).toString())
                .doesNotContain(USER_MARKER).doesNotContain(PASS_MARKER);
        // 默认端口与显式 3306 等价，主机不区分大小写
        WriteDataSourceSettings.from(props("jdbc:mysql://LOCALHOST/maccms_unit", "u", "p", null, 2),
                "jdbc:mysql://localhost:3306/maccms_unit?useSSL=false");
        // root 需要显式允许
        assertThatThrownBy(() -> WriteDataSourceSettings.from(props(READ_URL, "root", PASS_MARKER, null, null), READ_URL))
                .hasMessageContaining("MACCMS_WRITE_DB_ALLOW_ROOT").hasMessageNotContaining(PASS_MARKER);
        WriteDataSourceSettings.from(props(READ_URL, "root", PASS_MARKER, true, null), READ_URL);

        for (String[] bad : List.of(
                new String[] {"jdbc:mysql://127.0.0.1:3306/other_db", "同一主机"},
                new String[] {"jdbc:mysql://127.0.0.2:3306/maccms_unit", "同一主机"},
                new String[] {"jdbc:mysql://127.0.0.1:3307/maccms_unit", "同一主机"},
                new String[] {"jdbc:mysql://127.0.0.1:3306/", "jdbc:mysql://主机"},
                new String[] {"jdbc:mysql://h1,h2/maccms_unit", "jdbc:mysql://主机"},
                new String[] {"jdbc:mysql:loadbalance://127.0.0.1/maccms_unit", "jdbc:mysql://主机"},
                new String[] {"jdbc:mariadb://127.0.0.1/maccms_unit", "jdbc:mysql://主机"},
                new String[] {READ_URL + "&useAffectedRows=true", "useaffectedrows"},
                new String[] {READ_URL + "&allowMultiQueries=true", "allowmultiqueries"},
                new String[] {READ_URL + "&allowLoadLocalInfile=true", "allowloadlocalinfile"},
                new String[] {READ_URL + "&sessionVariables=sql_mode=''", "sessionvariables"},
                new String[] {READ_URL + "&user=x&password=" + PASS_MARKER, "user"},
                new String[] {READ_URL + "&Password=" + PASS_MARKER, "password"})) {
            assertThatThrownBy(() -> WriteDataSourceSettings.from(props(bad[0], "maccms_rw", "p", null, null), READ_URL))
                    .as(bad[0]).isInstanceOf(IllegalStateException.class).hasMessageContaining(bad[1])
                    .hasMessageNotContaining(PASS_MARKER).hasMessageNotContaining("other_db").hasMessageNotContaining("h1,h2");
        }
        assertThatThrownBy(() -> WriteDataSourceSettings.from(props(READ_URL, "u", "p", null, null), "jdbc:h2:mem:x"))
                .hasMessageContaining("MACCMS_DB_URL");
        assertThatThrownBy(() -> WriteDataSourceSettings.from(props(READ_URL, " u", "p", null, null), READ_URL))
                .hasMessageContaining("空白");
        for (int size : new int[] {0, 51}) {
            assertThatThrownBy(() -> WriteDataSourceSettings.from(props(READ_URL, "u", "p", null, size), READ_URL))
                    .hasMessageContaining("MACCMS_WRITE_DB_POOL_SIZE");
        }
    }

    private static WriteDataSourceProperties props(String url, String user, String password, Boolean allowRoot,
                                                   Integer pool) {
        return new WriteDataSourceProperties(true, url, user, password, allowRoot, pool);
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) {
            sb.append(x.getClass().getName()).append(": ").append(x.getMessage()).append('\n');
        }
        return sb.toString();
    }
}

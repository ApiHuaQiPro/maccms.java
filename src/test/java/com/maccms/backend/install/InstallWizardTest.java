package com.maccms.backend.install;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.common.write.WriteDatabase;
import com.maccms.backend.install.application.InstallSettings;
import com.maccms.backend.install.application.InstallSqlFile;
import com.maccms.backend.install.application.InstallWizard;
import com.maccms.backend.install.infrastructure.DeployConfigFile;
import com.maccms.backend.install.infrastructure.InstallConnections;
import com.maccms.backend.install.infrastructure.InstallTarget;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * APPK-2 S3-20 C-56 / API-030（技术设计 §6.14 INS-2 ①～⑥、INS-3）：不连外部 MySQL，用桩 {@link InstallConnections} 验证第 4 步与第 5 步的
 * 判定顺序——每个拒绝分支都不把任何语句送进 {@code executeInstallSql}、不写 {@code extra/maccms.php} 与安装锁；哈希一致时先写
 * {@code maccms.php}，再把 install.sql 切出的 55 条语句原样交给 {@code executeInstallSql}。真实库上的执行结果在 {@code InstallWizardMySqlIT}。
 */
class InstallWizardTest {

    private static final LegacyLang LANG = LegacyLang.of(LegacyLang.DEFAULT_CODE);
    private static final String DB = "maccms_it_00000000000000_00000000";

    private InstallGolden.Site site;
    private Stub connections;
    private InstallWizard wizard;
    private byte[] siteConfig;

    /** 桩：只读会话只回答 {@code SELECT VERSION()} 与 {@code mac_} 表数；建表只记录收到的语句；写库不可用。 */
    static final class Stub extends InstallConnections {
        /** {@code mac_} 表数；null 表示连不上。 */
        Long macTables = 0L;
        SqlResult result = SqlResult.SQL_FAILED;
        int executeCalls;
        List<String> executed = List.of();
        Runnable onExecute = () -> { };
        final List<String> queries = new ArrayList<>();

        @Override
        public Connection openServerReadOnly(InstallTarget t) {
            return connection();
        }

        @Override
        public Connection openReadOnly(InstallTarget t) throws SQLException {
            if (macTables == null) {
                throw new SQLException("连不上（测试）");
            }
            return connection();
        }

        @Override
        public SqlResult executeInstallSql(InstallTarget t, List<String> statements) {
            executeCalls++;
            executed = List.copyOf(statements);
            onExecute.run();
            return result;
        }

        @Override
        public WriteDatabase openWrite(InstallTarget t, boolean allowRoot) {
            throw new IllegalStateException("单测不连库");
        }

        private Connection connection() {
            return proxy(Connection.class, (m, args) -> switch (m) {
                case "createStatement" -> proxy(Statement.class, (sm, sargs) -> switch (sm) {
                    case "executeQuery" -> {
                        queries.add((String) sargs[0]);
                        yield resultSet();
                    }
                    default -> null;
                });
                case "isReadOnly" -> true;
                default -> null;
            });
        }

        private ResultSet resultSet() {
            boolean[] read = {false};
            return proxy(ResultSet.class, (m, args) -> switch (m) {
                case "next" -> {
                    boolean first = !read[0];
                    read[0] = true;
                    yield first;
                }
                case "getLong" -> macTables;
                case "wasNull" -> false;
                default -> null;
            });
        }
    }

    interface Handler {
        Object handle(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Handler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> type.getSimpleName() + "(stub)";
                };
            }
            return h.handle(m.getName(), args);
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        site = InstallGolden.Site.create();
        connections = new Stub();
        Path siteRoot = site.application().toRealPath().getParent();
        LegacyConfigProperties props = new LegacyConfigProperties(site.application().toString());
        wizard = new InstallWizard(InstallSettings.of(site.deployConfig().toString(), "false", "", siteRoot),
                new DeployConfigFile(site.deployConfig()), connections, new LegacyConfigService(props), new LegacySiteFiles(props));
        siteConfig = Files.readAllBytes(maccmsPhp());
    }

    @AfterEach
    void tearDown() throws Exception {
        site.delete();
    }

    private Path maccmsPhp() {
        return site.application().resolve("extra/maccms.php");
    }

    private Path installSql() {
        return site.application().resolve("install/sql/install.sql");
    }

    /** 第 4 步测试连接成功后写下的部署配置（这里直接写，免走第 4 步）。 */
    private void deployed() throws Exception {
        Files.writeString(site.deployConfig(), "MACCMS_DB_URL=jdbc:mysql://127.0.0.1:3306/" + DB + "?characterEncoding=UTF-8\n"
                + "MACCMS_DB_USERNAME=it_install\nMACCMS_DB_PASSWORD=test-only-password\n");
    }

    private InstallWizard.Reply install() {
        return wizard.install("admin", "Test-only-1", "Test-only-1".getBytes(StandardCharsets.UTF_8), "/", "1", () -> true, LANG);
    }

    /** 拒绝分支的共同结果：没有语句送去执行、站点配置与安装锁都没动。 */
    private void nothingExecutedOrWritten() throws Exception {
        assertThat(connections.executeCalls).as("送入 executeInstallSql 的次数").isZero();
        assertThat(Files.readAllBytes(maccmsPhp())).as("extra/maccms.php 未改").isEqualTo(siteConfig);
        assertThat(Files.exists(site.lock())).as("没有安装锁").isFalse();
    }

    // ------------------------------------------------------------------ 第 4 步

    /** INS-3：第 4 步只测连接并确认库存在，不再核对结构指纹——空库（没有任何表）通过。 */
    @Test
    void step4AcceptsAnEmptyDatabase() throws Exception {
        Map<String, String> post = new LinkedHashMap<>();
        post.put("hostname", "127.0.0.1");
        post.put("hostport", "3306");
        post.put("database", DB);
        post.put("username", "it_install");
        post.put("password", "test-only-password");
        post.put("prefix", "mac_");
        post.put("cover", "0");
        connections.macTables = 0L;
        InstallWizard.Reply r = wizard.testConnection(post, true, LANG);
        assertThat(r.success()).as(r.msg()).isTrue();
        assertThat(r.msg()).isEqualTo(LANG.get("install/database_name_haved"));
        assertThat(connections.queries).containsExactly("SELECT VERSION()");
        assertThat(Files.readString(site.deployConfig())).contains("MACCMS_DB_USERNAME=it_install");
        nothingExecutedOrWritten();
    }

    // ------------------------------------------------------------------ 第 5 步

    /** 互斥区内复查安装锁：令牌校验通过之后、进入互斥区之前另一次安装已写下安装锁 → 非法访问，不再连库。 */
    @Test
    void lockCreatedBeforeEnteringTheCriticalSectionIsRejected() throws Exception {
        deployed();
        InstallWizard.Reply r = wizard.install("admin", "Test-only-1", "Test-only-1".getBytes(StandardCharsets.UTF_8), "/", "1",
                () -> {
                    try {
                        Files.writeString(site.lock(), "2026-01-01 00:00:00");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return true;
                }, LANG);
        assertThat(r.success()).isFalse();
        assertThat(r.msg()).isEqualTo("非法访问");
        assertThat(connections.queries).isEmpty();
        assertThat(connections.executeCalls).isZero();
        assertThat(Files.readAllBytes(maccmsPhp())).isEqualTo(siteConfig);
    }

    /** INS-2 ①：数 {@code mac_} 表时连不上 → 数据库连接失败。 */
    @Test
    void connectFailureWhileCountingTables() throws Exception {
        deployed();
        connections.macTables = null;
        InstallWizard.Reply r = install();
        assertThat(r.success()).isFalse();
        assertThat(r.msg()).isEqualTo(LANG.get("install/database_connect_err"));
        nothingExecutedOrWritten();
    }

    /** INS-2 ②：目标库已有 {@code mac_} 表 → INSTALL_TABLES_EXIST“已存在数据表”。 */
    @Test
    void existingMacTablesAreRejected() throws Exception {
        deployed();
        connections.macTables = 1L;
        InstallWizard.Reply r = install();
        assertThat(r.success()).isFalse();
        assertThat(r.msg()).isEqualTo("已存在数据表");
        assertThat(connections.queries).singleElement().asString().contains("information_schema.TABLES")
                .contains("LIKE 'mac\\_%'");
        nothingExecutedOrWritten();
    }

    /** INS-2 ③：install.sql 缺失、改 1 字节、整文件 CRLF → INSTALL_SQL_HASH_MISMATCH“文件哈希不一致”。 */
    @Test
    void missingChangedOrCrlfInstallSqlIsAHashMismatch() throws Exception {
        deployed();
        byte[] original = Files.readAllBytes(installSql());
        assertThat(InstallSqlFile.matches(original)).as("临时站点里的 install.sql 为 LF 原样").isTrue();

        byte[] changed = original.clone();
        changed[100] ^= 1;
        byte[] crlf = new String(original, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        for (byte[] content : new byte[][] {null, changed, crlf}) {
            if (content == null) {
                Files.delete(installSql());
            } else {
                Files.write(installSql(), content);
            }
            InstallWizard.Reply r = install();
            assertThat(r.success()).isFalse();
            assertThat(r.msg()).isEqualTo("文件哈希不一致");
            nothingExecutedOrWritten();
        }
    }

    /** INS-2 ④：哈希一致时先写 maccms.php，再把 55 条语句（26 DROP + 26 CREATE + 3 INSERT）交给 executeInstallSql；失败不写安装锁。 */
    @Test
    void matchingFileWritesSiteConfigThenExecutesAllStatements() throws Exception {
        deployed();
        List<String> expected = InstallSqlFile.statements(Files.readAllBytes(installSql()));
        boolean[] configWrittenFirst = {false};
        connections.onExecute = () -> {
            try {
                configWrittenFirst[0] = !java.util.Arrays.equals(Files.readAllBytes(maccmsPhp()), siteConfig);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        Map<InstallConnections.SqlResult, String> cases = Map.of(
                InstallConnections.SqlResult.CONNECT_FAILED, LANG.get("install/database_connect_err"),
                InstallConnections.SqlResult.SQL_FAILED, LANG.get("install/init_err"));
        for (Map.Entry<InstallConnections.SqlResult, String> c : cases.entrySet()) {
            connections.result = c.getKey();
            connections.executeCalls = 0;
            configWrittenFirst[0] = false;
            InstallWizard.Reply r = install();
            assertThat(r.success()).isFalse();
            assertThat(r.msg()).as(c.getKey().name()).isEqualTo(c.getValue());
            assertThat(connections.executeCalls).isEqualTo(1);
            assertThat(connections.executed).isEqualTo(expected).hasSize(55);
            assertThat(connections.executed.stream().filter(s -> s.startsWith("DROP TABLE"))).hasSize(26);
            assertThat(connections.executed.stream().filter(s -> s.startsWith("CREATE TABLE"))).hasSize(26);
            assertThat(connections.executed.stream().filter(s -> s.startsWith("INSERT INTO"))).hasSize(3);
            assertThat(connections.executed).noneMatch(s -> s.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE DATABASE"));
            assertThat(configWrittenFirst[0]).as("执行前已写 extra/maccms.php").isTrue();
            assertThat(Files.readString(maccmsPhp())).contains("'install_dir' => '/'");
            assertThat(Files.exists(site.lock())).as("失败不写安装锁").isFalse();
        }
        // 执行成功但写初始数据时出错（单测里写库不可用）：初始失败，同样不写安装锁
        connections.result = InstallConnections.SqlResult.OK;
        InstallWizard.Reply r = install();
        assertThat(r.success()).isFalse();
        assertThat(r.msg()).isEqualTo(LANG.get("install/init_err"));
        assertThat(Files.exists(site.lock())).isFalse();
    }
}

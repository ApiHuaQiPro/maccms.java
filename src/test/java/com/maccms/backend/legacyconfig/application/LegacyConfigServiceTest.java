package com.maccms.backend.legacyconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.LegacyConfigException.Reason;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 配置目录、按修改时间热加载、失败不回退与仓库文件的整体读取。 */
class LegacyConfigServiceTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path maccms;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("application"));
        maccms = Files.createDirectories(root.resolve("extra")).resolve("maccms.php");
        write("<?php\nreturn array (\n  'site' => array ('site_name' => 'v1'),\n);", 1_000);
    }

    private void write(String text, long epochSecond) throws IOException {
        Files.writeString(maccms, text, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(maccms, FileTime.from(Instant.ofEpochSecond(epochSecond)));
    }

    private LegacyConfigService service() {
        return new LegacyConfigService(new LegacyConfigProperties(root.toString()));
    }

    @Test
    void unconfiguredServiceRefusesToRead() {
        for (String dir : new String[] {null, "", "  "}) {
            LegacyConfigService s = new LegacyConfigService(new LegacyConfigProperties(dir));
            assertThat(s.isConfigured()).isFalse();
            assertThatThrownBy(() -> s.read(LegacyConfigFile.MACCMS)).isInstanceOf(LegacyConfigException.class)
                    .extracting(e -> ((LegacyConfigException) e).reason()).isEqualTo(Reason.NOT_CONFIGURED);
        }
    }

    @Test
    void invalidDirectoryFailsAtStartupWithoutEchoingThePath() {
        String missing = tmp.resolve("test-only-missing").toString();
        assertThatThrownBy(() -> new LegacyConfigService(new LegacyConfigProperties(missing)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.legacy-config.dir")
                .hasMessageNotContaining("test-only-missing");
        assertThatThrownBy(() -> new LegacyConfigService(new LegacyConfigProperties(maccms.toString())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reloadsWhenModificationTimeOrSizeChanges() throws IOException {
        LegacyConfigService s = service();
        PhpArray first = s.read(LegacyConfigFile.MACCMS);
        assertThat(first.path("site", "site_name")).isEqualTo(new PhpString("v1"));
        assertThat(s.read(LegacyConfigFile.MACCMS)).as("未变化时返回缓存").isSameAs(first);

        write("<?php\nreturn array (\n  'site' => array ('site_name' => 'v2'),\n);", 2_000);
        assertThat(s.read(LegacyConfigFile.MACCMS).path("site", "site_name")).isEqualTo(new PhpString("v2"));

        // 修改时间不变但大小变化同样重新加载
        write("<?php\nreturn array (\n  'site' => array ('site_name' => 'v3-longer'),\n);", 2_000);
        assertThat(s.read(LegacyConfigFile.MACCMS).path("site", "site_name")).isEqualTo(new PhpString("v3-longer"));
    }

    @Test
    void brokenFileFailsInsteadOfServingStaleValuesAndRecoversWhenFixed() throws IOException {
        LegacyConfigService s = service();
        s.read(LegacyConfigFile.MACCMS);
        write("<?php\nreturn array ('site' => test_only_not_a_secret());", 3_000);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> s.read(LegacyConfigFile.MACCMS)).isInstanceOf(LegacyConfigException.class)
                    .hasMessageNotContaining("test_only_not_a_secret")
                    .extracting(e -> ((LegacyConfigException) e).reason()).isEqualTo(Reason.UNSUPPORTED_CONSTRUCT);
        }
        write("<?php\nreturn array ('site' => array ('site_name' => 'fixed'));", 4_000);
        assertThat(s.read(LegacyConfigFile.MACCMS).path("site", "site_name")).isEqualTo(new PhpString("fixed"));
    }

    @Test
    void oversizedAndMissingFiles() throws IOException {
        LegacyConfigService s = service();
        assertThatThrownBy(() -> s.read(LegacyConfigFile.ROUTE)).isInstanceOf(LegacyConfigException.class)
                .extracting(e -> ((LegacyConfigException) e).reason()).isEqualTo(Reason.NOT_FOUND);
        Files.write(maccms, new byte[VarExportParser.MAX_BYTES + 1]);
        assertThatThrownBy(() -> s.read(LegacyConfigFile.MACCMS)).isInstanceOf(LegacyConfigException.class)
                .extracting(e -> ((LegacyConfigException) e).reason()).isEqualTo(Reason.TOO_LARGE);
    }

    @Test
    void readsEveryConfigFileOfTheRepository() {
        LegacyConfigService s = new LegacyConfigService(new LegacyConfigProperties(Path.of("php", "application").toString()));
        for (LegacyConfigFile f : LegacyConfigFile.values()) {
            assertThat(s.read(f)).as(f.name()).isNotNull();
        }
        PhpArray m = s.read(LegacyConfigFile.MACCMS);
        assertThat(m.path("db", "tablepre")).isEqualTo(new PhpString("mac_"));
        assertThat(m.path("site", "install_dir")).isEqualTo(new PhpString("/"));
        assertThat(s.read(LegacyConfigFile.CAPTCHA).get("length")).isNotNull();
    }
}

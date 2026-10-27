package com.maccms.backend.legacyconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 配置写回（B15，24 文档第 3 节）：写出的内容必须能被新版读回，否则不写；基于旧版本的保存被拒绝；同一进程里的并发"读-改-写"串行执行、
 * 不丢更新；写回后立即读到新值（不等修改时间变化）。
 */
class LegacyConfigWriteTest {

    @TempDir
    Path tmp;

    private LegacyConfigService service;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        Path app = Files.createDirectories(tmp.resolve("application/extra")).getParent();
        file = app.resolve("extra/timming.php");
        Files.writeString(file, "<?php\nreturn array (\n  'n' => '0',\n);", StandardCharsets.UTF_8);
        service = new LegacyConfigService(new LegacyConfigProperties(app.toString()));
    }

    private static PhpArray with(String key, String value) {
        Map<Object, PhpValue> m = new LinkedHashMap<>();
        m.put(key, new PhpValue.PhpString(value));
        return new PhpArray(m);
    }

    @Test
    void unreadableContentIsNeverWritten() throws IOException {
        String before = Files.readString(file);
        LegacyConfigService.Snapshot s = service.snapshot(LegacyConfigFile.TIMMING);
        assertThatThrownBy(() -> service.replace(LegacyConfigFile.TIMMING, "<?php\nreturn array (".getBytes(StandardCharsets.UTF_8),
                s.version())).isInstanceOf(LegacyConfigException.class)
                .satisfies(e -> assertThat(((LegacyConfigException) e).reason()).isEqualTo(LegacyConfigException.Reason.INVALID_VALUE));
        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(service.generation()).isZero();
    }

    @Test
    void staleVersionIsRejectedAndFreshReadSeesTheWrite() throws IOException {
        LegacyConfigService.Snapshot s = service.snapshot(LegacyConfigFile.TIMMING);
        // 同样长度的新内容：不依赖修改时间或大小变化，写回后缓存即失效
        service.replace(LegacyConfigFile.TIMMING, VarExportWriter.macArr2File(with("n", "1")), s.version());
        assertThat(PhpValues.text(service.read(LegacyConfigFile.TIMMING).get("n"))).isEqualTo("1");
        assertThat(service.generation()).isEqualTo(1);
        assertThatThrownBy(() -> service.replace(LegacyConfigFile.TIMMING, VarExportWriter.macArr2File(with("n", "2")), s.version()))
                .isInstanceOf(LegacyConfigException.class)
                .satisfies(e -> assertThat(((LegacyConfigException) e).reason()).isEqualTo(LegacyConfigException.Reason.CONFLICT));
        assertThat(PhpValues.text(service.read(LegacyConfigFile.TIMMING).get("n"))).isEqualTo("1");
        assertThat(Files.readString(file)).isEqualTo("<?php\nreturn array (\n  'n' => '1',\n);");
    }

    @Test
    void concurrentUpdatesDoNotLoseWrites() throws Exception {
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(pool.submit(() -> service.update(LegacyConfigFile.TIMMING, current -> {
                long v = Long.parseLong(PhpValues.text(current.get("n")));
                return VarExportWriter.macArr2File(with("n", Long.toString(v + 1)));
            })));
        }
        for (Future<?> f : all) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(PhpValues.text(service.read(LegacyConfigFile.TIMMING).get("n"))).isEqualTo(Integer.toString(n));
        assertThat(service.generation()).isEqualTo(n);
    }

    @Test
    void externalChangeBetweenReadAndWriteIsAConflict() throws IOException {
        LegacyConfigService.Snapshot s = service.snapshot(LegacyConfigFile.TIMMING);
        // 仍在运行的旧 PHP（或手工）在两次之间改了文件
        Files.writeString(file, "<?php\nreturn array (\n  'n' => '9',\n);", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.replace(LegacyConfigFile.TIMMING, VarExportWriter.macArr2File(with("n", "1")), s.version()))
                .isInstanceOf(LegacyConfigException.class);
        assertThat(Files.readString(file)).contains("'9'");
    }
}

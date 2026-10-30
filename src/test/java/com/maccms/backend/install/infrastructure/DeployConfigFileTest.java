package com.maccms.backend.install.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 部署配置文件的写入与读回（31 文档第 5.3 节）：值原样读回，文件只有 ASCII、没有时间戳，写入是整体替换。 */
class DeployConfigFileTest {

    @Test
    void roundTripsSpecialValues(@TempDir Path dir) throws Exception {
        DeployConfigFile f = new DeployConfigFile(dir.resolve("maccms-deploy.properties"));
        assertThat(f.read()).isNull();
        assertThat(f.writable()).isTrue();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("MACCMS_DB_URL", "jdbc:mysql://127.0.0.1:3306/maccms_it_x?characterEncoding=UTF-8");
        m.put("MACCMS_DB_PASSWORD", " test-only #!=:\\密码\n\t");
        f.write(m);
        assertThat(f.read()).isEqualTo(m);
        String text = Files.readString(f.path(), StandardCharsets.ISO_8859_1);
        assertThat(text.chars().allMatch(c -> c < 0x80)).isTrue();
        assertThat(text).startsWith("# MacCMS backend deployment config").doesNotContainPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}");
        m.remove("MACCMS_DB_PASSWORD");
        f.write(m);
        assertThat(f.read()).isEqualTo(m);
        try (var s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("maccms-deploy.properties");
        }
    }

    @Test
    void notWritableWhenDirectoryMissing(@TempDir Path dir) {
        assertThat(new DeployConfigFile(dir.resolve("missing").resolve("x.properties")).writable()).isFalse();
    }
}

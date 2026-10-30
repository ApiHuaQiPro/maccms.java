package com.maccms.backend.staticgen.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * 定时任务的执行判断与 runtime 回写对 PHP 黄金向量（{@code staticgen/b16-php80-golden.json}，PHP 8.0.30 生成，7.3.33 相同）：
 * <ul>
 * <li>20 组任务/时刻/enforce 的"执行/跳过"与"上次执行时间"逐条一致；</li>
 * <li>从仓库的 {@code application/extra/timming.php} 出发，依次把 bb、aa 的 runtime 写成给定时间，写出的文件与 PHP {@code mac_arr2file}
 * 逐字节相同（{@link TimmingService#writeRuntime}，经 {@link LegacyConfigService#update} 原子写回）。</li>
 * </ul>
 */
class TimmingGoldenTest {

    static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    static final Path REPO_TIMMING = Path.of("php", "application", "extra", "timming.php");

    @Test
    void scheduleDecisionsMatchPhp() throws IOException {
        int n = 0;
        for (JsonNode s : StaticPathsGoldenTest.golden().get("schedules")) {
            Map<Object, PhpValue> t = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : s.get("task").properties()) {
                JsonNode v = e.getValue();
                t.put(e.getKey(), v.isIntegralNumber() ? new PhpValue.PhpInt(v.asLong()) : new PhpValue.PhpString(v.asText()));
            }
            TimmingSchedule.Decision d = TimmingSchedule.decide(new PhpArray(t), s.get("now").asLong(),
                    s.get("enforce").asText(), PRC, "从未");
            assertThat(d.due()).as(s.get("id").asText()).isEqualTo(s.get("due").asBoolean());
            assertThat(d.last()).as(s.get("id").asText()).isEqualTo(s.get("last").asText());
            n++;
        }
        assertThat(n).isEqualTo(20);
    }

    @Test
    void runtimeWritebackIsByteIdenticalToMacArr2File(@TempDir Path root) throws IOException {
        Path app = Files.createDirectories(root.resolve("application"));
        Files.createDirectories(app.resolve("extra"));
        // 仓库文件在 CRLF 检出时是 CRLF；解析不受影响，写出一律是 var_export 的 LF
        Files.copy(REPO_TIMMING, app.resolve("extra/timming.php"));
        LegacyConfigService config = new LegacyConfigService(new LegacyConfigProperties(app.toString()));
        LegacyLang lang = LegacyLang.of(LegacyLang.DEFAULT_CODE);
        for (JsonNode w : StaticPathsGoldenTest.golden().get("runtime").get("writes")) {
            TimmingService service = new TimmingService(config, lang,
                    Clock.fixed(Instant.ofEpochSecond(w.get("runtime").asLong()), PRC), PRC);
            assertThat(service.writeRuntime(w.get("set").asText(), w.get("runtime").asLong())).isTrue();
            assertThat(Files.readString(app.resolve("extra/timming.php"), StandardCharsets.UTF_8))
                    .as(w.get("set").asText()).isEqualTo(w.get("content").asText());
        }
        // 任务已被删除时不写
        assertThat(new TimmingService(config, lang, Clock.systemUTC(), PRC).writeRuntime("nope", 1L)).isFalse();
        assertThat(config.read(LegacyConfigFile.TIMMING).get("nope")).isNull();
    }
}

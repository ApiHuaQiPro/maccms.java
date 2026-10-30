package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code playerconfig.js}（B05，W1）：W1 打开时与 PHP 黄金样本逐字节一致（site/play/php-golden.json，由
 * src/test/php/vod_play_golden.php 按旧后台 configplay + _cache_clear 的写法生成），覆盖仓库旧配置与含外部解析/缓冲地址的合成配置；
 * W1 关闭时文件中没有任何外部主机，站内解析地址保留。
 */
class PlayerSettingsGoldenTest {

    private static final String SITE_HOST = "maccms.local.test";
    private static final Pattern HOST = Pattern.compile("(?i)(?:https?:)?\\\\?/\\\\?/([a-z0-9.-]+)");
    private static JsonNode golden;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = PlayerSettingsGoldenTest.class.getResourceAsStream("/site/play/php-golden.json")) {
            golden = JsonMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static PlayerSettings settings(String dir, boolean w1) {
        LegacyConfigService legacy = new LegacyConfigService(new LegacyConfigProperties(dir));
        return PlayerSettings.from(legacy.read(LegacyConfigFile.MACCMS), legacy.read(LegacyConfigFile.VODPLAYER),
                legacy.read(LegacyConfigFile.VODDOWNER), legacy.read(LegacyConfigFile.VODSERVER), w1, SITE_HOST);
    }

    @Test
    void repositoryConfigMatchesPhpByteForByte() {
        assertThat(settings("php/application", true).playerConfigScript())
                .isEqualTo(golden.get("playerConfigJs").asString());
        // 旧配置里没有外部地址时，两态相同
        assertThat(settings("php/application", false).playerConfigScript())
                .isEqualTo(golden.get("playerConfigJs").asString());
    }

    @Test
    void syntheticConfigWithExternalParseMatchesPhpWhenW1IsOpen() {
        String js = settings("src/test/resources/site/play/legacy-config", true).playerConfigScript();
        assertThat(js).isEqualTo(golden.get("playerConfigJsSynthetic").asString());
        assertThat(js).contains("jx.example.invalid", "ad.example.invalid", "jx2.example.invalid",
                "dlparse.example.invalid");
    }

    @Test
    void w1ClosedLeavesNoExternalHostButKeepsLocalAddresses() {
        String js = settings("src/test/resources/site/play/legacy-config", false).playerConfigScript();
        Matcher m = HOST.matcher(js);
        while (m.find()) {
            assertThat(m.group(1)).as("W1 关闭时 playerconfig.js 中的主机").isEqualTo(SITE_HOST);
        }
        assertThat(js).doesNotContain("example.invalid")
                .contains("\"prestrain\":\"\"", "\"parse\":\"\"")
                // 站内的缓冲页与本地解析地址保留（不是外部主机）
                .contains("\"buffer\":\"\\/static\\/player\\/loading.html\"", "\"parse\":\"\\/jx\\/?url=\"");
        // 结构不变：与 W1 打开时只差被清空的地址
        String open = settings("src/test/resources/site/play/legacy-config", true).playerConfigScript();
        assertThat(js.replaceAll("\"(parse|prestrain)\":\"[^\"]*\"", "\"$1\":\"\""))
                .isEqualTo(open.replaceAll("\"(parse|prestrain)\":\"[^\"]*\"", "\"$1\":\"\""));
    }

    @Test
    void parseSourcesAndPlayerOptionsFollowLegacyConfig() {
        PlayerSettings s = settings("src/test/resources/site/play/legacy-config", true);
        assertThat(s.parseFor("jxglobal")).contains("https://jx.example.invalid/?url=");
        assertThat(s.parseFor("jxown")).contains("http://jx2.example.invalid/p.php?u=");
        assertThat(s.parseFor("jxlocal")).contains("/jx/?url=");
        assertThat(s.parseFor("dplayer")).isEmpty();
        assertThat(s.parseFor("nosuch")).isEmpty();
        assertThat(s.prestrainAndBuffer()).containsExactly("//ad.example.invalid/pre.html", "/static/player/loading.html");
        assertThat(s.encrypt()).isZero();
        assertThat(s.playerSort()).isTrue();
        assertThat(s.players()).containsKeys("dplayer", "videojs", "iva", "offplayer");
        assertThat(s.players().get("offplayer")).containsEntry("status", "0");
    }

    @Test
    void unsupportedLegacyValuesFailWithoutEchoingThem() {
        PhpArray maccms = withApp("encrypt", new PhpValue.PhpString("SECRET-VALUE-3"));
        PhpArray empty = new PhpArray(Map.of());
        assertThatThrownBy(() -> PlayerSettings.from(maccms, empty, empty, empty, true, SITE_HOST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.encrypt")
                .hasMessageNotContaining("SECRET-VALUE-3");
        PhpArray sort = withApp("player_sort", new PhpValue.PhpString("2"));
        assertThatThrownBy(() -> PlayerSettings.from(sort, empty, empty, empty, true, SITE_HOST))
                .hasMessageContaining("app.player_sort");
        assertThat(PlayerSettings.unavailable().available()).isFalse();
    }

    private static PhpArray withApp(String key, PhpValue value) {
        Map<Object, PhpValue> app = new LinkedHashMap<>();
        app.put(key, value);
        Map<Object, PhpValue> root = new LinkedHashMap<>();
        root.put("app", new PhpArray(app));
        root.put("play", new PhpArray(Map.of()));
        return new PhpArray(root);
    }
}

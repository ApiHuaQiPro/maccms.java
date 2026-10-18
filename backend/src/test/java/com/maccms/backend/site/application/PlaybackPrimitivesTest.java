package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.PasswordTarget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B05 播放/下载的基础部件：PHP json_encode 兼容编码、旧 mac_escape、浏览器地址分类、播放器内页 CSP、访问密码票据与限流。
 */
class PlaybackPrimitivesTest {

    private static final String SITE = "maccms.local.test";
    private static final String SECRET = "synthetic-test-secret-0123456789-abcdefghij";

    // ------------------------------------------------------------------ PhpJson

    @Test
    void phpJsonMatchesJsonEncodeDefaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", "中/\"\\\b\f\n\r\t\u0001\u007f<>&'");
        m.put("n", 5);
        m.put("l", List.of(1L, true, false));
        m.put("z", null);
        assertThat(PhpJson.encode(m)).isEqualTo(
                "{\"a\":\"\\u4e2d\\/\\\"\\\\\\b\\f\\n\\r\\t\\u0001\u007f<>&'\",\"n\":5,\"l\":[1,true,false],\"z\":null}");
        // 放进 <script> 也不会提前结束元素；解析结果与默认编码相同
        String script = PhpJson.encodeForScript(Map.of("x", "</script><!--&' "));
        assertThat(script).isEqualTo("{\"x\":\"\\u003c\\/script\\u003e\\u003c!--\\u0026\\u0027\\u2028\"}");
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readTree(script)).isEqualTo(json.readTree(PhpJson.encode(Map.of("x", "</script><!--&' "))));
        // 代理对：PHP 写成两个 \\u
        assertThat(PhpJson.encode("😀")).isEqualTo("\"\\ud83d\\ude00\"");
        assertThat(PhpJson.phpFloat(1.0)).isEqualTo("1.0");
        assertThat(PhpJson.phpFloat(0.5)).isEqualTo("0.5");
        assertThatThrownBy(() -> PhpJson.encode(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ mac_escape

    @ParameterizedTest
    @CsvSource({
            "https://a.test/1.mp4, %68%74%74%70%73%3A%2F%2F%61%2E%74%65%73%74%2F%31%2E%6D%70%34",
            "视频, %u89C6%u9891",
            "a b, %61%20%62"})
    void macEscapeMatchesLegacyFunction(String in, String expected) {
        assertThat(LegacyPlayerData.macEscape(in)).isEqualTo(expected);
        assertThat(LegacyPlayerData.encode(in, 1)).isEqualTo(expected);
        assertThat(LegacyPlayerData.encode(in, 0)).isEqualTo(in);
    }

    @Test
    void base64OfEscapedUrlAndNonBmpCharacters() {
        // 与 PHP 黄金样本 play.v1.enc2 相同的地址
        assertThat(LegacyPlayerData.encode("/upload/vod/v1-3.mp4", 2))
                .isEqualTo("JTJGJTc1JTcwJTZDJTZGJTYxJTY0JTJGJTc2JTZGJTY0JTJGJTc2JTMxJTJEJTMzJTJFJTZEJTcwJTM0");
        assertThat(LegacyPlayerData.macEscape("😀")).isEqualTo("%u003F");
    }

    @Test
    void playerDataKeepsLegacyFieldOrderAndTypes() {
        Map<String, Object> m = LegacyPlayerData.build("play", 0, 3, 5, "/l", "/n", "", "名", "演", "导", "类",
                "https://a.test/1.mp4", "", "dplayer", "server1", "备注", "12", 2, 3);
        assertThat(m.keySet()).containsExactly("flag", "encrypt", "trysee", "points", "link", "link_next", "link_pre",
                "vod_data", "url", "url_next", "from", "server", "note", "id", "sid", "nid");
        JsonNode node = JsonMapper.builder().build().readTree(PhpJson.encode(m));
        assertThat(node.get("id").isString()).isTrue();
        assertThat(node.get("sid").isInt()).isTrue();
        assertThat(node.get("vod_data").properties()).extracting(Map.Entry::getKey)
                .containsExactly("vod_name", "vod_actor", "vod_director", "vod_class");
    }

    // ------------------------------------------------------------------ BrowserAddress

    @ParameterizedTest
    @CsvSource({
            "'', EMPTY",
            "/upload/a.mp4, LOCAL",
            "upload/a.mp4, LOCAL",
            "http://maccms.local.test/a.mp4, LOCAL",
            "https://MACCMS.LOCAL.TEST./a.mp4, LOCAL",
            "https://media.example.invalid/视频/1.mp4?a=1&b=2, EXTERNAL",
            "//cdn.example.invalid/a.mp4, EXTERNAL",
            "magnet:?xt=urn:btih:abc, OTHER_SCHEME",
            "thunder://QUFodHRw, OTHER_SCHEME",
            "javascript:alert(1), UNSAFE",
            "JavaScript:alert(1), UNSAFE",
            "'data:text/html;base64,AA', UNSAFE",
            "https://a.test/x\"onerror=1, UNSAFE",
            "https://a.test/<b>, UNSAFE",
            "https://user@a.test/x, UNSAFE",
            "https://a.test/a b, UNSAFE",
            "https://a.test/a\\b, UNSAFE",
            "http:///nohost, UNSAFE"})
    void classifiesAddresses(String raw, BrowserAddress.Kind kind) {
        assertThat(BrowserAddress.classify(raw, SITE).kind()).as(raw).isEqualTo(kind);
    }

    @Test
    void cspSourcesOnlyForAsciiHttpHosts() {
        assertThat(BrowserAddress.classify("https://Media.Example.Invalid:8443/a.mp4", SITE).cspSource())
                .contains("https://media.example.invalid:8443");
        assertThat(BrowserAddress.classify("//cdn.example.invalid/a", SITE).cspSource()).contains("cdn.example.invalid");
        assertThat(BrowserAddress.classify("https://例子.example/a", SITE).cspSource()).isEmpty();
        assertThat(BrowserAddress.classify("magnet:?xt=1", SITE).cspSource()).isEmpty();
    }

    // ------------------------------------------------------------------ PlayerFramePolicy

    @Test
    void playerFramePolicyHasPerResponseNonceAndOnlyRequestedSources() {
        PlayerFramePolicy a = PlayerFramePolicy.create();
        PlayerFramePolicy b = PlayerFramePolicy.create();
        assertThat(a.nonce()).isNotEqualTo(b.nonce()).hasSize(24);
        assertThat(a.header()).isEqualTo("default-src 'none'; script-src 'self' 'nonce-" + a.nonce() + "'; style-src "
                + "'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; frame-src 'self'; connect-src "
                + "'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'self'");
        assertThat(a.externalSources()).isEmpty();
        a.allowMedia("https://media.example.invalid").allowFrame("jx.example.invalid");
        assertThat(a.header()).contains("frame-src 'self' https://media.example.invalid jx.example.invalid")
                .contains("media-src 'self' blob: https://media.example.invalid")
                .doesNotContain("script-src 'self' 'nonce-" + a.nonce() + "' https");
        assertThat(a.header()).containsOnlyOnce("'unsafe-inline'").contains("style-src 'self' 'unsafe-inline'");
        a.allowIva();
        assertThat(a.header()).contains("script-src 'self' 'nonce-" + a.nonce() + "' *.videojj.com");
        assertThatThrownBy(() -> b.allowMedia("https://a.test; script-src *")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.allowFrame("'unsafe-inline'")).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ PasswordTickets

    private static Clock at(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }

    @Test
    void ticketsBindPurposeContentAndExpiry() {
        PasswordTickets t = PasswordTickets.from(SECRET, at(1_000_000));
        String ticket = t.issue(PasswordTarget.VIDEO_PLAY, 4, "Ab9");
        Optional<PasswordProof> proof = t.verify(PasswordTarget.VIDEO_PLAY, 4, ticket);
        assertThat(proof).isPresent();
        // 凭据 = SHA-256(salt + 密码)：与 MySQL SHA2(CONCAT(salt, 'Ab9'), 256) 相同的算法（PlaybackGoldenMySqlIT 在真实库上核对）
        assertThat(proof.get().fingerprint()).hasSize(64).doesNotContain("Ab9");
        assertThat(ticket).doesNotContain("Ab9");
        // 跨用途、跨内容
        assertThat(t.verify(PasswordTarget.VIDEO_DOWN, 4, ticket)).isEmpty();
        assertThat(t.verify(PasswordTarget.ARTICLE, 4, ticket)).isEmpty();
        assertThat(t.verify(PasswordTarget.VIDEO_PLAY, 5, ticket)).isEmpty();
        // 伪造：改签名、改到期、改凭据
        String[] parts = ticket.split("\\.");
        assertThat(t.verify(PasswordTarget.VIDEO_PLAY, 4, parts[0] + "." + parts[1] + "." + parts[2] + "."
                + "A".repeat(43))).isEmpty();
        assertThat(t.verify(PasswordTarget.VIDEO_PLAY, 4, parts[0] + "." + (Long.parseLong(parts[1]) + 1) + "."
                + parts[2] + "." + parts[3])).isEmpty();
        assertThat(t.verify(PasswordTarget.VIDEO_PLAY, 4, parts[0] + "." + parts[1] + "." + "0".repeat(64) + "."
                + parts[3])).isEmpty();
        // 另一个部署密钥签发的票据无效
        assertThat(PasswordTickets.from(SECRET + "x", at(1_000_000)).verify(PasswordTarget.VIDEO_PLAY, 4, ticket))
                .isEmpty();
        // 服务端有效期：到期即失效；到期时间超出有效期（伪造的长期票据）也无效
        long ttl = PasswordTickets.TTL.getSeconds();
        assertThat(PasswordTickets.from(SECRET, at(1_000_000 + ttl - 1)).verify(PasswordTarget.VIDEO_PLAY, 4, ticket))
                .isPresent();
        assertThat(PasswordTickets.from(SECRET, at(1_000_000 + ttl)).verify(PasswordTarget.VIDEO_PLAY, 4, ticket))
                .isEmpty();
        assertThat(PasswordTickets.from(SECRET, at(1_000_000 - Duration.ofDays(1).getSeconds()))
                .verify(PasswordTarget.VIDEO_PLAY, 4, ticket)).isEmpty();
        assertThat(t.toString()).doesNotContain(SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v1", "v1.1.2.3", "v2.9999999999.0000000000000000000000000000000000000000000000000000000000000000.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void malformedTicketsAreRejected(String ticket) {
        assertThat(PasswordTickets.from(SECRET, at(1_000)).verify(PasswordTarget.VIDEO_PLAY, 1, ticket)).isEmpty();
    }

    @Test
    void ticketsNeedADeploymentSecret() {
        PasswordTickets off = PasswordTickets.from("  ", Clock.systemUTC());
        assertThat(off.enabled()).isFalse();
        assertThat(off.verify(PasswordTarget.VIDEO_PLAY, 1, "v1.1.0.A")).isEmpty();
        assertThatThrownBy(() -> off.issue(PasswordTarget.VIDEO_PLAY, 1, "x")).isInstanceOf(IllegalStateException.class);
        assertThat(PasswordTickets.disabled().enabled()).isFalse();
        for (String bad : List.of("short-secret", "x".repeat(257), "with space in the middle of the secret 0123")) {
            assertThatThrownBy(() -> PasswordTickets.from(bad, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("maccms.site.password-tickets.secret")
                    .hasMessageNotContaining(bad);
        }
        assertThat(PasswordTickets.cookieName(PasswordTarget.VIDEO_DOWN, 7)).isEqualTo("maccms_pwd_v5_7");
    }

    // ------------------------------------------------------------------ PasswordAttempts

    @Test
    void attemptsAreLimitedPerTargetAndGlobally() {
        long[] now = {10_000};
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochSecond(now[0]);
            }
        };
        PasswordAttempts a = new PasswordAttempts(clock);
        for (int i = 0; i < PasswordAttempts.PER_TARGET; i++) {
            assertThat(a.tryAcquire(PasswordTarget.VIDEO_PLAY, 1)).isTrue();
            now[0]++;
        }
        assertThat(a.tryAcquire(PasswordTarget.VIDEO_PLAY, 1)).isFalse();
        // 其他用途、其他内容不受影响
        assertThat(a.tryAcquire(PasswordTarget.VIDEO_DOWN, 1)).isTrue();
        assertThat(a.tryAcquire(PasswordTarget.VIDEO_PLAY, 2)).isTrue();
        // 窗口过后恢复
        now[0] += PasswordAttempts.WINDOW_SECONDS;
        assertThat(a.tryAcquire(PasswordTarget.VIDEO_PLAY, 1)).isTrue();
        // 全局每秒上限
        PasswordAttempts g = new PasswordAttempts(clock);
        for (int i = 0; i < PasswordAttempts.GLOBAL_PER_SECOND; i++) {
            assertThat(g.tryAcquire(PasswordTarget.ARTICLE, 100 + i)).isTrue();
        }
        assertThat(g.tryAcquire(PasswordTarget.ARTICLE, 999)).isFalse();
        now[0]++;
        assertThat(g.tryAcquire(PasswordTarget.ARTICLE, 999)).isTrue();
    }
}

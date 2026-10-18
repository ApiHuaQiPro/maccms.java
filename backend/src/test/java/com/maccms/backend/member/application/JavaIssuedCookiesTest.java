package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.member.MemberGolden;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Java → PHP 方向的 cookie 互认（B08，17 文档第 4 节）：用 {@link MemberCookies#login} 为黄金样本中的合成会员（固定的
 * {@code user_random}）签发 cookie，按浏览器回送的形式（{@code 名=值; …}）写成 {@code member/java-issued.json}；
 * {@code member_golden.php} 把这些头原样交给旧 {@code label_user}/{@code checkLogin}，结果记在黄金的 {@code java_issued} 段。
 *
 * <p>本测试保证资源文件就是当前代码的输出（代码改了而文件没重新生成时失败；设环境变量 {@code MEMBER_GOLDEN_WRITE=1} 重新生成后需重跑
 * PHP 黄金脚本），并断言两个 PHP 版本都认这些 cookie——只有库里存的就是 {@code a&amp;b} 的会员例外：旧版自己签发的 cookie 它也不认
 * （{@code checkLogin} 对 cookie 再做一次 htmlspecialchars），与新版无关。
 */
class JavaIssuedCookiesTest {

    static final Path FILE = Path.of("src", "test", "resources", "member", "java-issued.json");
    static final long ISSUED_AT = 2_000_000_000L;
    static final List<Integer> USERS = List.of(1, 2, 3, 8, 11, 13, 15, 17, 18, 19, 21, 22);
    /** PHP 自己签发的 cookie 也不被自己接受的会员（login 黄金的 replay 为游客）。 */
    static final Set<Integer> LEGACY_SELF_REJECTED = Set.of(3);

    private static final Map<Integer, String> GROUP_NAMES = Map.of(1, "游客", 2, "默认会员", 3, "VIP会员");

    @Test
    void resourceIsTheCurrentOutput() throws IOException {
        String json = generate();
        if ("1".equals(System.getenv("MEMBER_GOLDEN_WRITE"))) {
            Files.writeString(FILE, json, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(FILE, StandardCharsets.UTF_8)).isEqualTo(json);
    }

    @Test
    void legacyPhpAcceptsJavaIssuedCookies() {
        for (String v : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            JsonNode replays = MemberGolden.load(v).get("java_issued");
            assertThat(replays).as(v + " 需用 java-issued.json 重新生成").isNotNull();
            assertThat(replays.size()).isEqualTo(USERS.size());
            for (JsonNode r : replays) {
                int id = r.get("user_id").asInt();
                boolean expected = !LEGACY_SELF_REJECTED.contains(id);
                assertThat(r.get("member").asBoolean()).as(v + " user " + id).isEqualTo(expected);
                if (expected) {
                    assertThat(r.get("replayed_user_id").asInt()).isEqualTo(id);
                }
            }
        }
    }

    static String generate() {
        JsonNode php = MemberGolden.load(MemberGolden.PHP80);
        List<String> entries = new ArrayList<>();
        for (int id : USERS) {
            JsonNode u = user(php, id);
            String name = MemberGolden.utf8(u.get("user_name"));
            int group = u.get("group_id").asInt();
            List<String> setCookies = MemberCookies.login(id, name, group, GROUP_NAMES.getOrDefault(group, ""),
                    u.get("user_random").asString(), "/static/images/touxiang.png", ISSUED_AT);
            List<String> pairs = setCookies.stream().map(h -> h.substring(0, h.indexOf(';'))).toList();
            entries.add("  {\"user_id\": " + id + ", \"header\": \""
                    + HexFormat.of().formatHex(String.join("; ", pairs).getBytes(StandardCharsets.UTF_8)) + "\"}");
        }
        return "[\n" + String.join(",\n", entries) + "\n]\n";
    }

    private static JsonNode user(JsonNode php, int id) {
        for (JsonNode u : php.get("users")) {
            if (u.get("user_id").asInt() == id) {
                return u;
            }
        }
        throw new IllegalStateException("no user " + id);
    }
}

package com.maccms.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.member.MemberGolden;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link PhpCookies} 对照 PHP 黄金样本（B08，17 文档第 3 节）：
 * <ul>
 * <li>parse：与 PHP 8.0 的 {@code $_COOKIE} 逐项相同；与 PHP 7.3 只在 {@code +} 的解码上不同（7.3 解成空格）。</li>
 * <li>setcookie：与 PHP 8.0 + ThinkPHP {@code cookie()} 写出的 {@code Set-Cookie} 逐字节相同（Max-Age 按 PHP 当时的秒差，
 * 比较时以黄金中的值为准）；PHP 7.3 用 {@code urlencode}（空格写成 {@code +}、{@code ~} 写成 {@code %7E}），解码后与新版写出的值相同。</li>
 * </ul>
 */
class PhpCookiesGoldenTest {

    private static final JsonNode PHP80 = MemberGolden.load(MemberGolden.PHP80);
    private static final JsonNode PHP73 = MemberGolden.load(MemberGolden.PHP73);

    @Test
    void parseMatchesPhp80Exactly() {
        int n = 0;
        for (JsonNode c : PHP80.get("parse")) {
            String header = MemberGolden.latin1(c.get("header"));
            assertThat(javaCookies(header)).as(header).isEqualTo(MemberGolden.cookies(c.get("cookies")));
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(30);
    }

    @Test
    void parseDiffersFromPhp73OnlyByPlusDecoding() {
        for (JsonNode c : PHP73.get("parse")) {
            String header = MemberGolden.latin1(c.get("header"));
            Map<String, String> php = MemberGolden.cookies(c.get("cookies"));
            Map<String, String> java = javaCookies(header);
            if (header.contains("+")) {
                // PHP 7.3 的 cookie 解析用 urldecode（+ → 空格），8.0 起用 rawurldecode
                Map<String, String> plusAsSpace = new LinkedHashMap<>();
                java.forEach((k, v) -> plusAsSpace.put(k, v.equals(MemberGolden.ARRAY) ? v : v.replace('+', ' ')));
                assertThat(plusAsSpace).as(header).isEqualTo(php);
            } else {
                assertThat(java).as(header).isEqualTo(php);
            }
        }
    }

    @Test
    void setCookieMatchesPhp80Bytes() {
        int n = 0;
        for (JsonNode c : PHP80.get("setcookie")) {
            if (c.has("label")) {
                continue;
            }
            JsonNode call = c.get("call");
            String value = MemberGolden.utf8(call.get("value"));
            List<String> php = headers(c.get("set_cookies"));
            if (value.isEmpty()) {
                // ThinkPHP 助手 cookie(name, '') 是读取，不写出；PhpCookies.setCookie 的空值按 PHP setcookie 写成删除
                assertThat(php).isEmpty();
                continue;
            }
            assertThat(php).hasSize(1);
            long requestTime = c.get("request_time").asLong();
            String expected = php.get(0);
            String maxAge = expected.replaceAll(".*Max-Age=(\\d+).*", "$1");
            long now = requestTime + 2592000L - Long.parseLong(maxAge);
            assertThat(PhpCookies.setCookie(call.get("name").asString(), value, requestTime + 2592000L, now, "/"))
                    .as(value).isEqualTo(expected);
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(13);
    }

    @Test
    void php73IssuedValuesDecodeToTheSameText() {
        for (JsonNode c : PHP73.get("setcookie")) {
            if (c.has("label") || c.get("set_cookies").isEmpty()) {
                continue;
            }
            String value = MemberGolden.utf8(c.get("call").get("value"));
            String php = headers(c.get("set_cookies")).get(0);
            String wire = php.substring(php.indexOf('=') + 1, php.indexOf(';'));
            assertThat(new String(PhpCookies.urlDecode(wire.getBytes(StandardCharsets.ISO_8859_1)),
                    StandardCharsets.UTF_8)).isEqualTo(value);
            assertThat(new String(PhpCookies.urlDecode(PhpCookies.rawUrlEncode(value)
                    .getBytes(StandardCharsets.ISO_8859_1)), StandardCharsets.UTF_8)).isEqualTo(value);
        }
    }

    @Test
    void deleteMatchesThinkPhpCookieDelete() {
        for (String version : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            for (JsonNode c : MemberGolden.load(version).get("setcookie")) {
                if ("logout".equals(c.path("label").asString(""))) {
                    assertThat(headers(c.get("set_cookies"))).containsExactly(
                            PhpCookies.deleteCookie("user_id", "/"), PhpCookies.deleteCookie("user_name", "/"),
                            PhpCookies.deleteCookie("group_id", "/"), PhpCookies.deleteCookie("group_name", "/"),
                            PhpCookies.deleteCookie("user_check", "/"), PhpCookies.deleteCookie("user_portrait", "/"));
                }
            }
        }
    }

    @Test
    void httpDateUsesPhpFormat() {
        assertThat(PhpCookies.httpDate(0)).isEqualTo("Thu, 01-Jan-1970 00:00:00 GMT");
        assertThat(PhpCookies.httpDate(1792304162L + 2592000L)).isEqualTo("Tue, 17-Nov-2026 06:16:02 GMT");
    }

    @Test
    void multipleHeadersAreJoinedAndFirstScalarWins() {
        Map<String, PhpCookies.Value> m = PhpCookies.parse(List.of("a=1; b=2", "a=3"));
        assertThat(m.get("a").text()).isEqualTo("1");
        assertThat(m.get("b").text()).isEqualTo("2");
        assertThat(PhpCookies.parse((String) null)).isEmpty();
        assertThat(Set.copyOf(PhpCookies.parse("x[]=1; y=2").keySet())).containsExactlyInAnyOrder("x", "y");
        assertThat(PhpCookies.parse("x[]=1").get("x").array()).isTrue();
    }

    static Map<String, String> javaCookies(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        PhpCookies.parse(header).forEach((k, v) -> out.put(k, v.array() ? MemberGolden.ARRAY
                : new String(v.bytes(), StandardCharsets.ISO_8859_1)));
        return out;
    }

    static List<String> headers(JsonNode hexList) {
        return StreamSupport.stream(hexList.spliterator(), false).map(MemberGolden::latin1).toList();
    }
}

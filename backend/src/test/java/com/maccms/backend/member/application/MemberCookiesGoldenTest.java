package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.member.MemberGolden;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 会员 cookie 的写出与校验值（B08，17 文档第 3、4 节）：
 * <ul>
 * <li>{@link MemberCookies#login} 与 PHP 8.0 真实登录（{@code User::login}，login_verify 关闭）写出的六个 {@code Set-Cookie} 逐字节相同
 * ——同一个 {@code user_random}、同一个签发时刻；所以新版签发的 cookie 被旧 PHP 读取时，与旧版自己签发的完全一样（Java → PHP 互认）。</li>
 * <li>{@link MemberCookies#logout}、{@link MemberCookies#guestReset} 与旧注销、{@code label_user} 失败回写相同。</li>
 * <li>{@link UserCheck} 与旧算法相同；旧版宽松比较会接受的 {@code 0e…} 形式在新版不被接受（D-B08-1）。</li>
 * </ul>
 */
class MemberCookiesGoldenTest {

    private static final Map<Integer, String> GROUP_NAMES = Map.of(1, "游客", 2, "默认会员", 3, "VIP会员");
    private static final String PORTRAIT = "/static/images/touxiang.png";

    @Test
    void loginCookiesMatchPhp80Bytes() {
        JsonNode php = MemberGolden.load(MemberGolden.PHP80);
        int compared = 0;
        for (JsonNode c : php.get("login")) {
            if (c.get("code").asInt() != 1) {
                continue;
            }
            List<String> headers = headers(c.get("set_cookies"));
            Map<String, PhpCookies.Value> sent = PhpCookies.parse(String.join("; ",
                    headers.stream().map(h -> h.substring(0, h.indexOf(';'))).toList()));
            long userId = Long.parseLong(sent.get("user_id").text());
            if (userId != c.get("user_id").asLong()) {
                // 输入 a+b 经旧版 urldecode 后登录到了 a b（user 13）：row_after 是被请求的那一行，不是登录进去的那一行
                continue;
            }
            JsonNode row = c.get("row_after");
            String dbName = userName(php, userId);
            int group = row.get("group_id").asInt();
            long expires = c.get("request_time").asLong() + MemberCookies.LOGIN_EXPIRE_SECONDS;
            List<String> java = MemberCookies.login(userId, dbName, group, GROUP_NAMES.get(group),
                    row.get("user_random").asString(), PORTRAIT, c.get("request_time").asLong());
            // Max-Age 是 PHP 写每个头时的 expires − time()，同一请求内可能跨秒（2592000 或 2591999），只比较其余字节
            assertThat(java.stream().map(MemberCookiesGoldenTest::withoutMaxAge).toList()).as("user " + userId)
                    .isEqualTo(headers.stream().map(MemberCookiesGoldenTest::withoutMaxAge).toList());
            assertThat(java).allMatch(h -> h.contains("Max-Age=2592000;"));
            assertThat(headers).allMatch(h -> h.matches(".*Max-Age=259(2000|1999|1998);.*"));
            assertThat(expires).isEqualTo(epochOf(headers.get(0)));
            compared++;
        }
        assertThat(compared).isGreaterThanOrEqualTo(8);
    }

    @Test
    void logoutAndGuestResetMatchPhp() {
        for (String v : List.of(MemberGolden.PHP80, MemberGolden.PHP73)) {
            for (JsonNode c : MemberGolden.load(v).get("setcookie")) {
                String label = c.path("label").asString("");
                if (label.equals("logout")) {
                    assertThat(MemberCookies.logout()).isEqualTo(headers(c.get("set_cookies")));
                } else if (label.equals("guest_reset")) {
                    assertThat(MemberCookies.guestReset()).isEqualTo(headers(c.get("set_cookies")));
                }
            }
        }
    }

    @Test
    void groupRefreshMatchesCheckLoginExpiryCookies() {
        JsonNode php = MemberGolden.load(MemberGolden.PHP80);
        for (JsonNode c : php.get("label_user")) {
            if (!c.get("label").asString().equals("valid-22")) {
                continue;
            }
            List<String> headers = headers(c.get("set_cookies"));
            assertThat(headers).hasSize(2);
            long expires = epochOf(headers.get(0));
            long maxAge = Long.parseLong(headers.get(0).replaceAll(".*Max-Age=(\\d+).*", "$1"));
            assertThat(MemberCookies.groupRefresh(2, "默认会员", expires - maxAge)).isEqualTo(headers);
        }
    }

    @Test
    void userCheckIsTheLegacyMd5AndComparesStrictly() {
        assertThat(UserCheck.of("b08ma4ctsl", "magic", 21)).isEqualTo("0e580925710392956858516910781754");
        assertThat(UserCheck.of("r2zh", "中文会员", 2)).isEqualTo(md5("r2zh-中文会员-2-"));
        assertThat(UserCheck.of("", "neverlogin", 10)).isEqualTo(md5("-neverlogin-10-"));
        String magic = UserCheck.of("b08ma4ctsl", "magic", 21);
        assertThat(UserCheck.matches(magic, magic)).isTrue();
        for (String loose : List.of("0e0", "00", "0e999", "0.0", "0E580925710392956858516910781754", magic + " ")) {
            assertThat(UserCheck.matches(magic, loose)).as(loose).isFalse();
        }
    }

    private static String withoutMaxAge(String header) {
        return header.replaceAll("Max-Age=[0-9]+", "Max-Age=N");
    }

    private static long epochOf(String header) {
        String date = header.replaceAll(".*expires=([^;]+);.*", "$1");
        return java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter
                .ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", java.util.Locale.ROOT)).toEpochSecond();
    }

    private static String userName(JsonNode php, long id) {
        for (JsonNode u : php.get("users")) {
            if (u.get("user_id").asLong() == id) {
                return MemberGolden.utf8(u.get("user_name"));
            }
        }
        throw new IllegalStateException("no user " + id);
    }

    private static List<String> headers(JsonNode hex) {
        return StreamSupport.stream(hex.spliterator(), false)
                .map(h -> new String(MemberGolden.bytes(h), StandardCharsets.UTF_8)).toList();
    }

    private static String md5(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpJson;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * B14 后台会员与财务服务里不依赖数据库的旧语义（23 文档第 4 节）。期望值是在 PHP 8.0.30 / 7.3.33（{@code date_default_timezone_set('PRC')}）上
 * 实测的结果（两版相同的写法）。经完整 HTTP 入口与真实 MySQL 的对照见 {@code AdminMemberGoldenMySqlIT}。
 */
class MemberAdminUnitTest {

    // ------------------------------------------------------------------ strtotime 子集（LegacyTime）

    @Test
    void formDateTimeMatchesPhpStrtotimeForTheWidgetFormats() {
        long now = 1_793_010_435L;
        Map<String, Long> php = new LinkedHashMap<>();
        php.put("2030-01-02 03:04:05", 1893524645L);
        php.put("2030-1-2 3:04:05", 1893524645L);
        php.put("2030-02-30", 1898611200L);
        php.put("2030-04-31 10:00", 1903831200L);
        php.put(" 2030-01-02 ", 1893513600L);
        php.put("2030-01-02T03:04", 1893524640L);
        php.put("2030-01-02T3:04:05", 1893524645L);
        php.put("2030-01-02 24:00", 1893600000L);
        php.put("2030-01-02 03:04:60", 1893524700L);
        php.put("2030-01-02 3:4", 1893524640L);
        php.put("2030-01-02\t03:04", 1893524640L);
        php.put("2030-01-02  03:04", 1893524640L);
        php.put("\n2030-01-02", 1893513600L);
        php.put("2030-1-02 03:04:5", 1893524645L);
        php.put("2030-00-10", 1891526400L);
        php.put("1969-12-31 00:00:00", -115200L);
        php.put("9999-12-31 23:59:59", 253402271999L);
        // strtotime 失败（false）→ 写库为 0
        php.put("", 0L);
        php.put("2030-13-01", 0L);
        php.put("2030-01-32", 0L);
        php.put("2030-01-02 25:00", 0L);
        php.put("2030-01-02 03:60", 0L);
        php.put("2030-01-02 03:04:61", 0L);
        php.put("2030-01-02 03", 0L);
        // 只有空白：strtotime 返回当前时间
        php.put("   ", now);
        php.forEach((s, v) -> assertThat(LegacyTime.formDateTime(s, now)).as(s).isEqualTo(v));
    }

    @Test
    void otherStrtotimeFormatsAreRejected() {
        for (String s : List.of("now", "+1 day", "2030/01/02", "tomorrow", "1 January 2030", "2030-01-02 03:04 PM")) {
            assertThatThrownBy(() -> LegacyTime.formDateTime(s, 0)).as(s).isInstanceOf(AdminRejectedException.class);
        }
    }

    @Test
    void daysAgoMidnightMatchesTheLegacyTimeFilter() {
        long base = 1_800_000_000L;
        // strtotime(date('Y-m-d', strtotime('-'.$t.' day')))（PRC）
        assertThat(LegacyTime.daysAgoMidnight("0", base)).isEqualTo(1799942400L);
        assertThat(LegacyTime.daysAgoMidnight("7", base)).isEqualTo(1799337600L);
        assertThat(LegacyTime.daysAgoMidnight("30", base)).isEqualTo(1797350400L);
        assertThat(LegacyTime.daysAgoMidnight("1", base)).isEqualTo(1799856000L);
        // '- day'、'-abc day' 失败：date('Y-m-d', false) = 1970-01-01 → -28800
        for (String bad : new String[] {"", "abc", null, "1.5", "123456"}) {
            assertThat(LegacyTime.daysAgoMidnight(bad, base)).as(String.valueOf(bad)).isEqualTo(-28800L);
        }
    }

    // ------------------------------------------------------------------ 卡号（mac_get_rndstr）

    @Test
    void cardNumbersUseTheLegacyCharsetsAndNeverStartWithZero() {
        // 第一个字符抽到 '0'（混合字符集里的下标 8）时换成 1～9：随机源依次给出 8、5、0、1（"0723"），再给出 2（换成 '1' + 2）
        int[] seq = {8, 5, 0, 1, 2, 3, 4, 6};
        RandomGenerator fixed = new RandomGenerator() {
            int i;

            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public int nextInt(int bound) {
                return seq[i++ % seq.length] % bound;
            }
        };
        CardAdminService cards = new CardAdminService(null, null, null, fixed);
        String s = cards.randomString(4, "234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        assertThat(s).isEqualTo("3723");
        CardAdminService secure = new CardAdminService(null, null, null, new java.security.SecureRandom());
        for (int i = 0; i < 200; i++) {
            assertThat(secure.randomString(16, "234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ")).matches("[1-9A-Z][02-9A-Z]{15}");
            assertThat(secure.randomString(16, "1234567890")).matches("[1-9][0-9]{15}");
            assertThat(secure.randomString(8, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")).matches("[A-Z]{8}");
        }
    }

    // ------------------------------------------------------------------ 会员组权限（group_popedom）

    @Test
    void popedomJsonMatchesPhpJsonEncode() {
        assertThat(PhpJson.encode(GroupAdminService.jsonValue(map("1", map("1", b("1"), "3", b("3")), "6", map("2", b("2"))))))
                .isEqualTo("{\"1\":{\"1\":\"1\",\"3\":\"3\"},\"6\":{\"2\":\"2\"}}");
        // 键恰好是 0、1… 时 PHP 输出数组
        assertThat(PhpJson.encode(GroupAdminService.jsonValue(map("5", map("0", b("1"), "1", b("2"))))))
                .isEqualTo("{\"5\":[\"1\",\"2\"]}");
        assertThat(PhpJson.encode(GroupAdminService.jsonValue(map("0", map("1", b("1")))))).isEqualTo("[{\"1\":\"1\"}]");
        assertThat(PhpJson.encode(GroupAdminService.jsonValue(map("0", b("a"), "2", b("b"))))).isEqualTo("{\"0\":\"a\",\"2\":\"b\"}");
        assertThat(PhpJson.encode(GroupAdminService.jsonValue(map("01", b("x"))))).isEqualTo("{\"01\":\"x\"}");
        assertThat(PhpJson.encode(GroupAdminService.jsonValue(b("a/b\"中")))).isEqualTo("\"a\\/b\\\"\\u4e2d\"");
        assertThat(PhpJson.encode(GroupAdminService.jsonValue(map("k", b("中/文"))))).isEqualTo("{\"k\":\"\\u4e2d\\/\\u6587\"}");
        assertThatThrownBy(() -> GroupAdminService.jsonValue(new byte[] {(byte) 0xff})).isInstanceOf(AdminRejectedException.class);
    }

    @Test
    void popedomCheckboxesFollowTheDecodedJson() {
        Object decoded = PhpJson.decode("{\"1\":{\"1\":\"1\",\"3\":\"3\"},\"5\":[\"1\",\"2\"],\"6\":{\"2\":\"0\"}}");
        assertThat(GroupAdminService.popedomChecked(decoded, "1", "1")).isTrue();
        assertThat(GroupAdminService.popedomChecked(decoded, "1", "2")).isFalse();
        assertThat(GroupAdminService.popedomChecked(decoded, "5", "1")).as("[\"1\",\"2\"][1]").isTrue();
        assertThat(GroupAdminService.popedomChecked(decoded, "5", "2")).isFalse();
        assertThat(GroupAdminService.popedomChecked(decoded, "6", "2")).as("'0' 为 empty").isFalse();
        assertThat(GroupAdminService.popedomChecked(PhpJson.decode("\"abc\""), "1", "1")).isFalse();
        assertThat(GroupAdminService.popedomChecked(PhpJson.decode(""), "1", "1")).isFalse();
        assertThat(GroupAdminService.popedomChecked(PhpJson.decode("[{\"1\":\"1\"}]"), "0", "1")).isTrue();
    }

    @Test
    void registrationGroupComparisonIsPhpLoose() {
        // 仓库与旧系统配置页都没有 user.reg_group：null == '' 为真（新增会员组被强制启用），null == '3' 为假
        assertThat(GroupAdminService.regGroupEquals(null, b(""))).isTrue();
        assertThat(GroupAdminService.regGroupEquals(null, null)).isTrue();
        assertThat(GroupAdminService.regGroupEquals(null, b("3"))).isFalse();
        assertThat(GroupAdminService.regGroupEquals(null, b("0"))).isFalse();
        assertThat(GroupAdminService.regGroupEquals(new PhpValue.PhpString("3"), b("3.0"))).isTrue();
        assertThat(GroupAdminService.regGroupEquals(new PhpValue.PhpString("3"), b("4"))).isFalse();
        assertThat(GroupAdminService.regGroupEquals(new PhpValue.PhpInt(3), b(" 3"))).isTrue();
        assertThat(GroupAdminService.regGroupEquals(new PhpValue.PhpString(""), null)).isTrue();
    }

    // ------------------------------------------------------------------ 推广访问的来源前缀（Visit::index）

    @Test
    void visitPrefixesReproduceTheLegacySubstrComparison() {
        assertThat(prefixes("a.test")).containsExactly("http://a.test", "https://a.test");
        // substr($a, 5) === 'http:'（第 5 个字节之后的部分），几乎总是不成立
        assertThat(prefixes("http://a.test")).containsExactly("http://http://a.test", "https://http://a.test");
        assertThat(prefixes("xxxxxhttp:")).containsExactly("xxxxxhttp:", "xxxxxhttps:");
        assertThat(prefixes("yyyyyhttps")).containsExactly("yyyyyhttps", "yyyyyhttps");
        assertThat(MemberAdminSupport.prefixLike("a_t%")).isEqualTo("a_t%%");
        assertThat(MemberAdminSupport.prefixLike("a!b")).isEqualTo("a!!b%");
    }

    @Test
    void firstPageFollowsTheLooseComparisonAfterIntval() {
        assertThat(UserAdminService.firstPage(PhpForm.empty())).isTrue();
        assertThat(UserAdminService.firstPage(PhpForm.empty().put("page", "1.0"))).isTrue();
        assertThat(UserAdminService.firstPage(PhpForm.empty().put("page", "abc"))).as("intval < 1 按 1").isTrue();
        assertThat(UserAdminService.firstPage(PhpForm.empty().put("page", "0"))).isTrue();
        assertThat(UserAdminService.firstPage(PhpForm.empty().put("page", "2"))).isFalse();
        assertThat(UserAdminService.firstPage(PhpForm.empty().put("page", "2.0"))).isFalse();
        assertThat(UserAdminService.firstPage(PhpForm.empty().put("page", "1abc"))).as("PHP 8：'1abc' == 1 为假").isFalse();
    }

    @Test
    void adminPasswordIsMd5OfTheRawBytes() {
        assertThat(UserAdminService.md5(" pa ss ".getBytes(StandardCharsets.UTF_8))).isEqualTo("c31ff7d77259816dec4d5ebfec1c8df1");
        assertThat(UserAdminService.md5("like ".getBytes(StandardCharsets.UTF_8))).isEqualTo("3a885345c8ad352a48e940181a21d264");
        assertThat(UserAdminService.md5(new byte[0])).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    private static List<String> prefixes(String wd) {
        byte[][] ab = MemberLogAdminService.visitPrefixes(wd.getBytes(StandardCharsets.UTF_8));
        return List.of(new String(ab[0], StandardCharsets.UTF_8), new String(ab[1], StandardCharsets.UTF_8));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}

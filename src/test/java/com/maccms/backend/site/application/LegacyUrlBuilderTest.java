package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 生成器的输入规则与输出安全性。逐字一致性由 {@link LegacyUrlGoldenVectorsTest} 覆盖。 */
class LegacyUrlBuilderTest {

    private static final LegacyUrlBuilder B = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT);

    @Test
    void pageRoutes() {
        assertThat(B.path(new SiteRoute.Home())).isEmpty();
        assertThat(B.path(new SiteRoute.VodType(5, 1))).isEqualTo("index.php/vod/type/id/5.html");
        assertThat(B.path(new SiteRoute.VodType(5, 2))).isEqualTo("index.php/vod/type/id/5/page/2.html");
        assertThat(B.path(new SiteRoute.VodDetail(8))).isEqualTo("index.php/vod/detail/id/8.html");
    }

    @Test
    void outOfRangeInputsAreRejected() {
        assertThatThrownBy(() -> B.vodType(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodType(65536, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodType(1, 1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodDetail(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodDetail(4294967296L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodPlay(1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodSearch(Map.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodShow(0, Map.of(), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownParametersAndActionsAreRejected() {
        assertThatThrownBy(() -> B.vodSearch(Map.of("id", "1"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodSearch(Map.of("page", "2"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B.vodShow(1, Map.of("wd", "x"), 1)).isInstanceOf(IllegalArgumentException.class);
        for (String action : new String[] {"", "Login", "../x", "a/b", "login.html", "x".repeat(33)}) {
            assertThatThrownBy(() -> B.user(action)).as(action).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> B.user(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyValuesAreDroppedLikePhpEmpty() {
        Map<String, String> m = new HashMap<>();
        m.put("wd", null);
        m.put("area", "");
        m.put("year", "0");
        assertThat(B.vodSearch(m, 1)).isEqualTo("index.php/vod/search.html");
        // 只有空白的值保留在参数里，但 url() 拼接时跳过（Url.php:143）
        assertThat(B.vodSearch(Map.of("wd", " \t"), 1)).isEqualTo("index.php/vod/search.html");
    }

    @Test
    void loneSurrogateIsRejected() {
        assertThatThrownBy(() -> B.vodSearch(Map.of("wd", "a\uD800b"), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "abcXYZ019-_.|abcXYZ019-_.",
            "a b|a+b",
            "~*'()!|%7E%2A%27%28%29%21",
            "中|%E4%B8%AD",
            "😀|%F0%9F%98%80"})
    void urlencodeMatchesPhp(String in, String out) {
        assertThat(LegacyUrlBuilder.urlencode(in)).isEqualTo(out);
    }

    @Test
    void hostileValuesStaySitePaths() {
        String[] hostile = {"javascript:alert(1)", "data:text/html,x", "//evil.invalid", "http://evil.invalid/",
                "\"><script>x</script>", "a b", "%2e%2e", "&#x2F;"};
        for (LegacyUrlStyle style : new LegacyUrlStyle[] {LegacyUrlStyle.REPO_DEFAULT,
                new LegacyUrlStyle(true, true, false, '/'), new LegacyUrlStyle(true, false, true, '/'),
                new LegacyUrlStyle(false, false, false, '-')}) {
            LegacyUrlBuilder b = new LegacyUrlBuilder(style);
            for (String v : hostile) {
                String p;
                try {
                    p = b.vodSearch(Map.of("wd", v), 1);
                } catch (IllegalArgumentException e) {
                    continue; // 拒绝也是安全结果（例如编码后含 %2F）
                }
                assertThat(SitePaths.isValid(p)).as(v).isTrue();
                assertThat(SafeUrl.parse("/" + p)).as(v).hasValueSatisfying(u -> assertThat(u.isAbsolute()).isFalse());
                assertThat(p).as(v).doesNotContain(":", "<", ">", "\"", "'", "//");
            }
        }
    }
}

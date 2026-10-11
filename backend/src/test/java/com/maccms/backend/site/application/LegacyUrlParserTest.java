package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 解析器的边界：恶意与不规范输入、空值、分页、越界、canonical（不依赖请求头）。旧行为对照见黄金向量测试。 */
class LegacyUrlParserTest {

    private static final LegacyUrlBuilder DEFAULT = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT);
    private static final LegacyUrlParser PARSER = new LegacyUrlParser(DEFAULT);
    private static final LegacyUrlParser REWRITE_ROUTE = new LegacyUrlParser(
            new LegacyUrlBuilder(new LegacyUrlStyle(true, false, true, '/')));

    private static String outcome(LegacyUrlParser parser, String path, String query) {
        return LegacyUrlGoldenVectorsTest.outcome(parser, path, query);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "/|NULL|home|true",
            "/|''|home|true",
            "/index.php|NULL|home|false",
            "/index.php/|NULL|home|false",
            "/|s=|home|false",
            "/index.php/vod/type/id/7.html|NULL|type 7 1|true",
            "/index.php/vod/type/id/7/page/3.html|NULL|type 7 3|true",
            "/index.php/vod/type/id/7/page/1.html|NULL|type 7 1|false",
            "/index.php/vod/type/page/3/id/7.html|NULL|type 7 3|false",
            "/index.php/vod/type/id/65535/page/1000.html|NULL|type 65535 1000|true",
            "/index.php/vod/detail/id/4294967295.html|NULL|detail 4294967295|true",
            "/index.php/vod/detail/id/9|NULL|detail 9|false",
            "/index.php/vod/detail/id/9/|NULL|detail 9|false",
            "/|s=%2Fvod%2Fdetail%2Fid%2F9|detail 9|false",
            "/index.php|s=vod/detail/id/9.html|detail 9|false",
            "/index.php/index/index.html|NULL|home|false"})
    void recognisedForms(String path, String query, String expected, boolean canonical) {
        assertThat(outcome(PARSER, path, query)).isEqualTo(expected);
        assertThat(PARSER.parse(path, query).orElseThrow().canonical()).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 不以单个 / 开头、绝对形式、协议相对
            "", "index.php/vod/detail/id/1.html", "http://evil.invalid/index.php/vod/detail/id/1.html",
            "//evil.invalid/index.php/vod/detail/id/1.html", "///index.php/vod/detail/id/1.html",
            // 不规范路径：空段、点段、反斜线、编码字符、控制字符、空白
            "/index.php//vod/detail/id/1.html", "/index.php/vod//detail/id/1.html", "/index.php/./vod/detail/id/1.html",
            "/index.php/vod/../vod/detail/id/1.html", "/index.php/vod/detail/id/1.html/..", "/index.php\\vod/detail/id/1.html",
            "/index.php/vod/detail/id/%31.html", "/index.php/vod%2Fdetail/id/1.html", "/index.php/vod/detail/id/1%00.html",
            "/index.php/vod/detail/id/1\r\n.html", "/index.php/vod/detail/id/1 .html", "/index.php/vod/detail/id/1\u0000",
            // 大小写、其他后缀、其他控制器、拼音 id、非数字页码
            "/index.php/VOD/DETAIL/id/1.html", "/index.php/vod/detail/id/1.HTML", "/index.php/vod/detail/id/1.htm",
            "/index.php/vod/detail/id/1.html.html", "/index.php/vod/play/id/1/sid/1/nid/1.html",
            "/index.php/vod/search/wd/x.html", "/index.php/vod/show/id/1.html", "/index.php/user/login.html",
            "/index.php/vod/detail/id/hechengshipin.html", "/index.php/vod/type/id/1/page/abc.html",
            "/index.php/vod/type/id/-1.html", "/index.php/vod/type/id/1/page/+2.html",
            // 当前配置下不认的形态：路由别名、去 index.php、- 分隔
            "/index.php/voddetail/1.html", "/vod/detail/id/1.html", "/index.php/vod-detail-id-1.html",
            "/index.php/vod", "/index.php/vod/play.html", "/index.php/.html", "/index.php/index.html",
            "/index.php/index/index/page/2.html", "/index.php5/vod/detail/id/1.html", "/admin.php/vod/detail/id/1.html"})
    void unrecognisedFormsAreLeftToPhp(String path) {
        assertThat(PARSER.parse(path, null)).as(path).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "s=//evil.invalid/vod/detail/id/1", "s=http://evil.invalid/", "s=javascript:alert(1)", "s=vod/detail/id/1%0A",
            "s=vod%5Cdetail/id/1", "s=vod/detail/id/1/../2", "s=%2E%2E/vod/detail/id/1", "s=%FF", "s=%E5%90", "s=%G0",
            "s=%2", "s=vod-detail-id-1", "s=VOD/DETAIL/ID/1", "s=vod detail"})
    void maliciousCompatParameterIsNotRecognised(String query) {
        assertThat(PARSER.parse("/", query)).as(query).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // 越界与 0、前导 0
            "/index.php/vod/type/id/0.html|''|id",
            "/index.php/vod/type/id/65536.html|''|id",
            "/index.php/vod/type/id/01.html|''|id",
            "/index.php/vod/type/id/1/page/0.html|''|page",
            "/index.php/vod/type/id/1/page/1001.html|''|page",
            "/index.php/vod/type/id/1/page/007.html|''|page",
            "/index.php/vod/detail/id/0.html|''|id",
            "/index.php/vod/detail/id/4294967296.html|''|id",
            "/index.php/vod/detail/id/99999999999999999999.html|''|id",
            // 未知、重复、缺失、不成对的参数
            "/index.php/vod/type/id/1/foo/2.html|''|foo",
            "/index.php/vod/detail/id/1/page/2.html|''|page",
            "/index.php/vod/type/id/1/id/2.html|''|id",
            "/index.php/vod/type/page/2.html|''|id",
            "/index.php/vod/type.html|''|id",
            "/index.php/vod/type/id/1/page.html|''|path",
            // 查询串（旧代码中会覆盖路径参数）
            "/index.php/vod/type/id/1.html|page=2|query",
            "/index.php/vod/detail/id/1.html|id=2|query",
            "/index.php/vod/detail/id/1.html|utm_source=x|query",
            "/|from=x|query",
            "/|s=vod/detail/id/1&from=x|query"})
    void recognisedFormWithBadParameterUsesExistingErrorSemantics(String path, String query, String field) {
        assertThatThrownBy(() -> PARSER.parse(path, query))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(e.fieldErrors()).extracting(ApiException.FieldError::field).containsExactly(field);
                });
    }

    @Test
    void routeAliasStyleRecognisesOnlyAliases() {
        assertThat(outcome(REWRITE_ROUTE, "/vodtype/3.html", null)).isEqualTo("type 3 1");
        assertThat(outcome(REWRITE_ROUTE, "/vodtype/3-2.html", null)).isEqualTo("type 3 2");
        assertThat(outcome(REWRITE_ROUTE, "/index.php/vodtype/3-2.html", null)).isEqualTo("type 3 2");
        assertThat(outcome(REWRITE_ROUTE, "/voddetail/5.html", null)).isEqualTo("detail 5");
        assertThat(outcome(REWRITE_ROUTE, "/", "s=voddetail/5")).isEqualTo("detail 5");
        assertThat(REWRITE_ROUTE.parse("/vodtype/3-2.html", null).orElseThrow().canonical()).isTrue();
        assertThat(REWRITE_ROUTE.parse("/index.php/vodtype/3-2.html", null).orElseThrow().canonical()).isFalse();
        // 旧站此时对模块路径直接 404；Java 不认
        assertThat(REWRITE_ROUTE.parse("/vod/type/id/3.html", null)).isEmpty();
        assertThat(REWRITE_ROUTE.parse("/index.php/vod/detail/id/5.html", null)).isEmpty();
        assertThat(REWRITE_ROUTE.parse("/vodtype/3-.html", null)).isEmpty();
        assertThat(REWRITE_ROUTE.parse("/vodtype/hechengfenlei.html", null)).isEmpty();
        assertThat(REWRITE_ROUTE.parse("/index-2.html", null)).isEmpty();
        assertThatThrownBy(() -> REWRITE_ROUTE.parse("/vodtype/3-1001.html", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> REWRITE_ROUTE.parse("/voddetail/0.html", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void canonicalComesFromBaseUrlNeverFromRequest() {
        // 解析器的输入只有路径与查询串：Host、X-Forwarded-Host、Forwarded、请求协议无从影响结果
        SiteSettings site = SiteSettings.from(new SiteProperties("true", "合成站", "https://maccms.local.test/sub/",
                null, null, null, null, null, null, null, null));
        for (String path : new String[] {"/index.php/vod/detail/id/9.html", "/index.php/vod/detail/id/9",
                "/index.php/vod/detail/id/9/"}) {
            LegacyUrlParser.Match m = PARSER.parse(path, null).orElseThrow();
            assertThat(site.canonical(m.path()).href())
                    .isEqualTo("https://maccms.local.test/sub/index.php/vod/detail/id/9.html");
            assertThat(site.publicPath(m.path()).href()).isEqualTo("/sub/index.php/vod/detail/id/9.html");
        }
        assertThat(site.canonical(PARSER.parse("/", "s=vod/type/id/2/page/3").orElseThrow().path()).href())
                .isEqualTo("https://maccms.local.test/sub/index.php/vod/type/id/2/page/3.html");
        assertThat(site.canonical(PARSER.parse("/index.php", null).orElseThrow().path()).href())
                .isEqualTo("https://maccms.local.test/sub/");
    }

    @Test
    void rejectsOverlongInput() {
        assertThat(PARSER.parse("/index.php/vod/detail/id/1" + "/a".repeat(600), null)).isEmpty();
        assertThat(PARSER.parse("/", "s=" + "a".repeat(2000))).isEmpty();
        assertThat(PARSER.parse(null, null)).isEmpty();
    }

    @Test
    void stylesThatAreNotCoveredByVectorsAreRejected() {
        assertThatThrownBy(() -> new LegacyUrlStyle(false, false, true, '-')).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyUrlStyle(false, false, false, '_')).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyUrlBuilder(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Optional.of(LegacyUrlStyle.REPO_DEFAULT))
                .hasValue(new LegacyUrlStyle(false, false, false, '/'));
    }
}

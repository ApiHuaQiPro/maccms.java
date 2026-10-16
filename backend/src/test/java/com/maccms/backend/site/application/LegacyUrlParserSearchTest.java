package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.VideoSort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link LegacyUrlParser#parseSearch} 的解析向量（09 方案第 7.2.3、7.2.7 节），四种 URL 形态（默认、suffixHide、
 * rewrite、两者都开）各执行一遍。结果三类：认得（条件 + 页码 + 是否 canonical）/ empty（交 PHP）/ INVALID_PARAMETER（400）。
 */
class LegacyUrlParserSearchTest {

    static final List<LegacyUrlStyle> STYLES = List.of(new LegacyUrlStyle(false, false, false, '/'),
            new LegacyUrlStyle(false, true, false, '/'), new LegacyUrlStyle(true, false, false, '/'),
            new LegacyUrlStyle(true, true, false, '/'));

    static Stream<LegacyUrlStyle> styles() {
        return STYLES.stream();
    }

    private static LegacyUrlParser parser(LegacyUrlStyle style) {
        return new LegacyUrlParser(new LegacyUrlBuilder(style));
    }

    /** 搜索基址（带前导 /），即每种形态下生成器的固定路径。 */
    private static String base(LegacyUrlStyle style) {
        return "/" + new LegacyUrlBuilder(style).vodSearch(Map.of(), 1);
    }

    private static LegacyUrlParser.Match match(LegacyUrlStyle style, String query) {
        return parser(style).parseSearch(base(style), query).orElseThrow();
    }

    private static SearchFilters filters(LegacyUrlStyle style, String query) {
        return ((SiteRoute.VodSearch) match(style, query).route()).filters();
    }

    private static ApiException.FieldError invalid(LegacyUrlStyle style, String query) {
        try {
            parser(style).parseSearch(base(style), query);
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
            assertThat(e.fieldErrors()).hasSize(1);
            // 错误信息不回显输入
            assertThat(e.getMessage()).isEqualTo("请求参数不合法");
            return e.fieldErrors().getFirst();
        }
        throw new AssertionError("应为 INVALID_PARAMETER：" + query);
    }

    // ---------------------------------------------------------------- 路径

    @ParameterizedTest
    @MethodSource("styles")
    void acceptsOnlyTheFixedSearchPaths(LegacyUrlStyle style) {
        LegacyUrlParser p = parser(style);
        for (String path : List.of("/index.php/vod/search.html", "/index.php/vod/search/", "/index.php/vod/search")) {
            assertThat(p.parseSearch(path, "wd=a")).as(path).isPresent();
        }
        for (String path : List.of("/vod/search.html", "/vod/search/", "/vod/search")) {
            assertThat(p.parseSearch(path, "wd=a").isPresent()).as(path).isEqualTo(style.rewrite());
        }
        for (String path : List.of("/index.php/vod/search/wd/x.html", "/index.php/vod/search/wd/x", "/vod/search/wd/x/",
                "/index.php/vodsearch/x-------------.html", "/index.php/vod-search.html", "/index.php/VOD/SEARCH.html",
                "/index.php/vod/search.htm", "/index.php/vod/search.HTML", "/index.php/vod/search//",
                "/index.php/vod/search.html/", "/index.php//vod/search.html", "/index.php/vod/./search.html",
                "/index.php/vod/search%2Ehtml", "/index.php/vod/search.html;jsessionid=x", "/index.php",
                "/index.php/vod/searchx", "index.php/vod/search.html", "/index.php/vod/show.html",
                "/index.php/vod/search.html" + "x".repeat(1100))) {
            assertThat(p.parseSearch(path, "wd=x")).as(path).isEmpty();
        }
        assertThat(p.parseSearch(null, "wd=x")).isEmpty();
        // 三页的解析器仍不认识 vod/search
        assertThat(p.parse(base(style), null)).isEmpty();
        assertThat(p.parse(base(style), "wd=x")).isEmpty();
    }

    @Test
    void unsupportedStylesNeverMatch() {
        assertThat(new LegacyUrlParser(new LegacyUrlBuilder(new LegacyUrlStyle(false, false, true, '/')))
                .parseSearch("/index.php/vod/search.html", "wd=a")).isEmpty();
        assertThat(new LegacyUrlParser(new LegacyUrlBuilder(new LegacyUrlStyle(false, false, false, '-')))
                .parseSearch("/index.php/vod/search.html", "wd=a")).isEmpty();
    }

    // ---------------------------------------------------------------- 第 7.2.7 节解析向量

    static Stream<Arguments> acceptedValues() {
        return Stream.of(
                // 原始查询串 → 解码一次并归一后的 wd
                Arguments.of("wd=A/B", "A/B"),
                Arguments.of("wd=A%2FB", "A/B"),
                Arguments.of("wd=A%5CB", "A\\B"),
                Arguments.of("wd=.", "."),
                Arguments.of("wd=..", ".."),
                Arguments.of("wd=A.html", "A.html"),
                Arguments.of("wd=a.htmlb", "a.htmlb"),
                Arguments.of("wd=%2541", "%41"),
                Arguments.of("wd=%252F", "%2F"),
                Arguments.of("wd=C%2B%2B", "C++"),
                Arguments.of("wd=C++", "C"),
                Arguments.of("wd=%e4%b8%ad", "中"),
                Arguments.of("wd=%E3%80%80a", "a"),
                Arguments.of("wd=a+b", "a b"),
                Arguments.of("wd=Love%2C+Death+%26+Robots", "Love, Death & Robots"),
                Arguments.of("wd=a%3Db%26c", "a=b&c"),
                Arguments.of("wd=!%25_", "!%_"),
                Arguments.of("wd=~*'()", "~*'()"));
    }

    @ParameterizedTest
    @MethodSource("acceptedValues")
    void decodesExactlyOnceAndNormalises(String query, String keyword) {
        for (LegacyUrlStyle style : STYLES) {
            SearchFilters f = filters(style, query);
            assertThat(f.keyword()).as(style + " " + query).isEqualTo(keyword);
            assertThat(f.area()).isNull();
        }
    }

    @Test
    void backslashAndDotSegmentsAreValuesNotPathStructure() {
        for (LegacyUrlStyle style : STYLES) {
            // 原始 \ 在 0x21-0x7E 内（浏览器通常会编码成 %5C），按普通字符接受；点段只是值，不是路径段
            assertThat(filters(style, "wd=A\\B").keyword()).isEqualTo("A\\B");
            assertThat(filters(style, "area=..&wd=x").area()).isEqualTo("..");
        }
    }

    @Test
    void nonCanonicalFormsStillMatchWithCanonicalFalse() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(match(style, "wd=%E4%B8%AD").canonical()).isTrue();
            assertThat(match(style, "wd=%e4%b8%ad").canonical()).as("小写十六进制").isFalse();
            assertThat(match(style, "wd=C%2B%2B").canonical()).isTrue();
            assertThat(match(style, "wd=+C%2B%2B+").canonical()).as("首尾空白").isFalse();
            assertThat(match(style, "wd=x&area=a").canonical()).as("键顺序").isFalse();
            assertThat(match(style, "area=a&wd=x").canonical()).isTrue();
            assertThat(match(style, "wd=x&page=1").canonical()).as("page=1").isFalse();
            assertThat(match(style, "wd=x&").canonical()).as("空项").isFalse();
            assertThat(match(style, "wd=x&page=2").canonical()).as("page 不在规范位置").isFalse();
            assertThat(match(style, "page=2&wd=x").canonical()).isTrue();
            assertThat(match(style, "level=0000000009").canonical()).isFalse();
            assertThat(match(style, "level=9").canonical()).isTrue();
            assertThat(match(style, null).canonical()).isTrue();
            assertThat(match(style, "").canonical()).isTrue();
            assertThat(match(style, "wd=").canonical()).as("wd 为空：条件为空，规范地址无查询串").isFalse();
            // 不是搜索基址的形态（如 rewrite 下带 /index.php，或另两种后缀）只输出 canonical
            for (String other : List.of("/index.php/vod/search", "/index.php/vod/search/", "/index.php/vod/search.html")) {
                if (!other.equals(base(style))) {
                    assertThat(parser(style).parseSearch(other, "wd=x").orElseThrow().canonical()).as(other).isFalse();
                }
            }
        }
    }

    @Test
    void zeroAndEmptyAreUnsetLikeOldEmpty() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(filters(style, "wd=0")).isEqualTo(SearchFilters.NONE);
            assertThat(filters(style, "wd=&area=&lang=0&year=+0+&level=0&class=%E3%80%80&by=0&order=")).isEqualTo(
                    SearchFilters.NONE);
            assertThat(filters(style, "wd")).as("没有 = 的项值为空").isEqualTo(SearchFilters.NONE);
            assertThat(((SiteRoute.VodSearch) match(style, "page=").route()).page()).isEqualTo(1);
        }
    }

    @Test
    void levelSortOrderAndPage() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(filters(style, "level=0000000009").level()).isEqualTo(9);
            assertThat(filters(style, "level=09").level()).isEqualTo(9);
            assertThat(invalid(style, "level=00000000009").field()).isEqualTo("level");
            assertThat(invalid(style, "level=00").field()).isEqualTo("level");
            assertThat(invalid(style, "level=10").field()).isEqualTo("level");
            assertThat(invalid(style, "level=%2B9").field()).isEqualTo("level");
            assertThat(filters(style, "level=+9+").level()).as("首尾空白先裁掉").isEqualTo(9);
            SearchFilters s = filters(style, "by=hits_month&order=asc");
            assertThat(s.sort()).isEqualTo(VideoSort.HITS_MONTH);
            assertThat(s.order()).isEqualTo(SearchFilters.SortOrder.ASC);
            assertThat(filters(style, "by=time").sort()).isEqualTo(VideoSort.TIME);
            assertThat(filters(style, "order=desc").order()).isEqualTo(SearchFilters.SortOrder.DESC);
            assertThat(invalid(style, "by=TIME").field()).isEqualTo("sort");
            assertThat(invalid(style, "by=vod_time").field()).isEqualTo("sort");
            assertThat(invalid(style, "order=ASC").field()).isEqualTo("order");
            SiteRoute.VodSearch p = (SiteRoute.VodSearch) match(style, "page=1000&wd=x").route();
            assertThat(p.page()).isEqualTo(1000);
            assertThat(match(style, "page=1").canonical()).isFalse();
            for (String bad : List.of("page=01", "page=0", "page=1001", "page=-1", "page=1.0", "page=%201", "page=1+",
                    "page=99999999999", "page=" + "9".repeat(30), "page=abc")) {
                assertThat(invalid(style, bad).field()).as(bad).isEqualTo("page");
            }
        }
    }

    @Test
    void textRulesAreDelegatedToContent() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(invalid(style, "wd=" + "%E4%B8%AD".repeat(65)).field()).isEqualTo("keyword");
            assertThat(filters(style, "wd=" + "%E4%B8%AD".repeat(64)).keyword()).hasSize(64);
            assertThat(invalid(style, "area=a%2Cb").field()).as("精确字段不支持多值").isEqualTo("area");
            assertThat(filters(style, "wd=a%2Cb").keyword()).as("关键字中的逗号是字面字符").isEqualTo("a,b");
            assertThat(invalid(style, "lang=" + "a".repeat(11)).field()).isEqualTo("lang");
            assertThat(invalid(style, "year=20").field()).isEqualTo("year");
            assertThat(invalid(style, "year=2020-2022").field()).isEqualTo("year");
            assertThat(filters(style, "year=+2020+").year()).isEqualTo("2020");
            assertThat(invalid(style, "class=" + "a".repeat(33)).field()).isEqualTo("classes");
            assertThat(invalid(style, "wd=a%00b").field()).as("控制字符").isEqualTo("keyword");
            assertThat(invalid(style, "wd=%EF%BF%BD").field()).as("U+FFFD").isEqualTo("keyword");
            assertThat(invalid(style, "wd=%F0%9F%98%80").field()).as("U+FFFF 以上").isEqualTo("keyword");
            // 内部空白保留
            assertThat(filters(style, "area=a+b").area()).isEqualTo("a b");
        }
    }

    @Test
    void malformedEncodingIs400ForThatKey() {
        for (LegacyUrlStyle style : STYLES) {
            for (String bad : List.of("wd=%FF", "wd=%E4%B8", "wd=%", "wd=%4", "wd=%G1", "wd=%C0%AF", "wd=a b",
                    "wd=中", "wd=a\tb", "wd=a\u007Fb")) {
                assertThat(invalid(style, bad).field()).as(bad).isEqualTo("wd");
            }
            assertThat(invalid(style, "area=%FF&wd=x").field()).isEqualTo("area");
        }
    }

    @Test
    void unknownDuplicateAndHandoffKeys() {
        for (LegacyUrlStyle style : STYLES) {
            for (String q : List.of("foo=1", "keyword=x", "classes=x", "sort=hits", "pageSize=10", "categoryId=1",
                    "utm_source=x", "WD=x", "w%64=x", "wd[]=x", "=x")) {
                ApiException.FieldError e = invalid(style, q);
                assertThat(e.field()).as(q).isEqualTo("query");
                assertThat(e.message()).isEqualTo("不支持的参数");
            }
            assertThat(invalid(style, "wd=&wd=x").field()).isEqualTo("wd");
            assertThat(invalid(style, "wd=&wd=x").message()).isEqualTo("参数重复");
            assertThat(invalid(style, "page=2&page=2").field()).isEqualTo("page");
            // 只报第一个错误（按出现顺序）
            assertThat(invalid(style, "foo=1&wd=%FF").field()).isEqualTo("query");
            assertThat(invalid(style, "wd=%FF&foo=1").field()).isEqualTo("wd");
            assertThat(invalid(style, "wd=a&wd=%FF").field()).isEqualTo("wd");
            // 交 PHP：兼容模式 s、首批不承接的旧键、rnd（任何位置）
            for (String q : List.of("s=/vod/detail/id/1", "wd=x&s=vod/search", "actor=x", "director=x", "tag=x",
                    "letter=A", "state=x", "version=x", "tid=1", "name=x", "ids=1", "isend=1", "ajax=1", "by=rnd",
                    "by=+rnd+", "foo=1&actor=x", "wd=%FF&tid=1")) {
                assertThat(parser(style).parseSearch(base(style), q)).as(q).isEmpty();
            }
            // rnd 判断在第 5 步之后：前面的错误先报
            assertThat(invalid(style, "foo=1&by=rnd").field()).isEqualTo("query");
        }
    }

    @Test
    void rawQueryLongerThan2048IsHandedOff() {
        for (LegacyUrlStyle style : STYLES) {
            String ok = "wd=x&" + "&".repeat(LegacyUrlParser.MAX_SEARCH_QUERY - 5);
            assertThat(ok).hasSize(2048);
            assertThat(parser(style).parseSearch(base(style), ok)).isPresent();
            assertThat(parser(style).parseSearch(base(style), ok + "&")).isEmpty();
            assertThat(parser(style).parseSearch(base(style), "foo=" + "x".repeat(2045))).isEmpty();
        }
    }

    @Test
    void maximumCombinationParses() {
        String q = "area=" + "%E6%B1%89".repeat(20) + "&by=hits_month&class=" + "%E6%B1%89".repeat(32)
                + "&lang=" + "%E6%B1%89".repeat(10) + "&level=9&order=desc&page=1000&wd=" + "%E6%B1%89".repeat(64)
                + "&year=2020";
        assertThat(q).hasSize(1209);
        for (LegacyUrlStyle style : STYLES) {
            LegacyUrlParser.Match m = match(style, q);
            assertThat(m.canonical()).isTrue();
            SiteRoute.VodSearch r = (SiteRoute.VodSearch) m.route();
            assertThat(r.page()).isEqualTo(1000);
            assertThat(r.filters()).isEqualTo(new SearchFilters("汉".repeat(64), "汉".repeat(20), "汉".repeat(10), "2020",
                    9, "汉".repeat(32), VideoSort.HITS_MONTH, SearchFilters.SortOrder.DESC));
        }
    }

    @Test
    void searchSwitchIsNotCheckedByTheParser() {
        // 开关在 content 查询时判断（SSR 页面同样显示 400），解析器只检查格式
        Optional<LegacyUrlParser.Match> m = parser(STYLES.getFirst()).parseSearch("/index.php/vod/search.html", "class=x");
        assertThat(m).isPresent();
    }

    @Test
    void routeRejectsOutOfRangePage() {
        assertThatThrownBy(() -> new SiteRoute.VodSearch(SearchFilters.NONE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteRoute.VodSearch(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteRoute.VodSearch(SearchFilters.NONE, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

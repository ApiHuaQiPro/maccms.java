package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoSort;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B05（MACC-29，队长裁定 Q2）：SSR 搜索页承接 B04 的新键、tid、by=rnd 与逐键零值归一
 * （docs/architecture/15-b05-readonly-pages.md 第 3 节）。四种 URL 形态各执行一遍；tid 的 intval 由 PHP 黄金向量
 * （content/vodtag/php-golden.json）逐项锁定；链接生成与回读不变式覆盖新键（固定种子随机条件）与查询串预算。
 */
class LegacyUrlParserSearchB05Test {

    private static final List<LegacyUrlStyle> STYLES = LegacyUrlParserSearchTest.STYLES;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static LegacyUrlParser parser(LegacyUrlStyle style) {
        return new LegacyUrlParser(new LegacyUrlBuilder(style));
    }

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
            assertThat(e.getMessage()).isEqualTo("请求参数不合法");
            return e.fieldErrors().getFirst();
        }
        throw new AssertionError("应为 INVALID_PARAMETER：" + query);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 新键

    @Test
    void newKeysAreAcceptedAndPassedToContentUnchanged() {
        for (LegacyUrlStyle style : STYLES) {
            SearchFilters f = filters(style, "actor=" + enc("演员甲,演员乙") + "&director=" + enc("导演")
                    + "&tag=" + enc("标签,") + "&letter=0-9&state=" + enc("正片") + "&version=" + enc("高清版,")
                    + "&name=" + enc("影片") + "&ids=1,,2&tid=6");
            assertThat(f.actor()).isEqualTo("演员甲,演员乙");
            assertThat(f.director()).isEqualTo("导演");
            assertThat(f.tag()).isEqualTo("标签,");
            assertThat(f.letter()).isEqualTo("0-9");
            assertThat(f.state()).isEqualTo("正片");
            assertThat(f.version()).isEqualTo("高清版,");
            assertThat(f.name()).isEqualTo("影片");
            assertThat(f.ids()).isEqualTo("1,,2");
            assertThat(f.tid()).isEqualTo(6L);
            assertThat(f.toVideoFilter().letters()).hasSize(10);
            assertThat(f.toLegacyConditions()).isEqualTo(VideoLegacyConditions.builder().tid(6L).build());
            assertThat(f.usesContainsMatch()).isTrue();
            assertThat(f.firstContainsMatchParameter()).isEqualTo("actor");
            assertThat(filters(style, "letter=A").usesContainsMatch()).as("精确匹配不受开关").isFalse();
            assertThat(filters(style, "tid=1").usesContainsMatch()).isFalse();
        }
    }

    @Test
    void contentRulesStillApplyToNewKeysInParameterOrder() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(invalid(style, "ids=abc").field()).isEqualTo("ids");
            assertThat(invalid(style, "ids=" + "1,".repeat(100) + "1").field()).isEqualTo("ids");
            assertThat(invalid(style, "letter=" + "A".repeat(65)).field()).isEqualTo("letter");
            assertThat(invalid(style, "actor=%01").field()).isEqualTo("actor");
            assertThat(invalid(style, "actor=" + enc("a b") + "&ids=x").field()).as("只报第一个错误（content 顺序）")
                    .isEqualTo("ids");
            assertThat(invalid(style, "name=a&name=b").field()).as("重复").isEqualTo("name");
            assertThat(invalid(style, "tid=1&tid=2").field()).isEqualTo("tid");
        }
    }

    /**
     * ThinkPHP 兼容模式 s：值是搜索页路径时按搜索页处理（入口路径与搜索路径都可），指向其他页面时交还 PHP；isend 旧代码读不到，
     * 按未知参数 400（不静默丢弃）；ajax 按 D-B05-6 接受并忽略（见 {@link #ajaxIsAcceptedAndIgnoredLikeLegacyIntval}）。
     */
    @Test
    void compatModeAndUnusedLegacyKeys() {
        for (LegacyUrlStyle style : STYLES) {
            LegacyUrlParser p = parser(style);
            var viaSearchPath = p.parseSearch(base(style), "s=vod/search&wd=x").orElseThrow();
            assertThat(((SiteRoute.VodSearch) viaSearchPath.route()).filters().keyword()).isEqualTo("x");
            assertThat(viaSearchPath.canonical()).isFalse();
            for (String s : List.of("s=vod/search", "s=%2Fvod%2Fsearch.html", "s=vod/search/")) {
                var m = p.parseSearch("/index.php", s + "&wd=y&page=2").orElseThrow();
                assertThat(((SiteRoute.VodSearch) m.route()).filters().keyword()).as(s).isEqualTo("y");
                assertThat(((SiteRoute.VodSearch) m.route()).page()).isEqualTo(2);
            }
            assertThat(p.parseSearch("/", "s=vod/search&wd=y")).isPresent();
            // 入口路径没有 s、s 指向其他页面：不是搜索页
            assertThat(p.parseSearch("/index.php", "wd=y")).isEmpty();
            for (String q : List.of("s=vod/detail/id/1&wd=x", "s=/vod/searchx", "s=%FF", "wd=x&s=vod/type/id/1")) {
                assertThat(p.parseSearch(base(style), q)).as(q).isEmpty();
                assertThat(p.parseSearch("/index.php", q)).as(q).isEmpty();
            }
            assertThat(invalid(style, "s=vod/search&s=vod/search").field()).isEqualTo("s");
            for (String q : List.of("isend=1", "isend=0&wd=x", "mid=1", "sid=1", "nid=1", "starsign=x")) {
                ApiException.FieldError e = invalid(style, q);
                assertThat(e.field()).as(q).isEqualTo("query");
                assertThat(e.message()).isEqualTo("不支持的参数");
            }
        }
    }

    /**
     * D-B05-6：旧 mac_param_url 对 ajax 只做 intval（application/common.php:1568），不进 listCacheData。新版接受任意取值（0、非数字、负数）
     * 并忽略：条件、链接、canonical、隐藏字段都与不带 ajax 时相同；重复、非法编码、总长度限制照旧；不因此放宽其他未知键。
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ajax=1", "ajax=0", "ajax=abc", "ajax=-1", "ajax=", "ajax"})
    void ajaxIsAcceptedAndIgnoredLikeLegacyIntval(String ajax) {
        for (LegacyUrlStyle style : STYLES) {
            LegacyUrlParser p = parser(style);
            var with = p.parseSearch(base(style), "wd=x&" + ajax + "&page=2").orElseThrow();
            var without = p.parseSearch(base(style), "wd=x&page=2").orElseThrow();
            assertThat(with.route()).isEqualTo(without.route());
            assertThat(with.canonical()).isFalse();
            assertThat(with.path()).isEqualTo(without.path());
            SearchFilters f = ((SiteRoute.VodSearch) with.route()).filters();
            assertThat(f.params()).doesNotContainKey("ajax");
            // 只有 ajax 时是无条件页
            assertThat(((SiteRoute.VodSearch) p.parseSearch(base(style), ajax).orElseThrow().route()).filters().isEmpty())
                    .isTrue();
        }
    }

    @Test
    void ajaxKeepsDuplicateEncodingAndUnknownKeyRules() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(invalid(style, "ajax=1&ajax=1").field()).isEqualTo("ajax");
            assertThat(invalid(style, "ajax=%FF").field()).isEqualTo("ajax");
            assertThat(invalid(style, "ajax=1&isend=1").field()).isEqualTo("query");
            assertThat(invalid(style, "ajax=1&foo=1").message()).isEqualTo("不支持的参数");
            assertThat(parser(style).parseSearch(base(style), "ajax=" + "1".repeat(2050))).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 逐键零值归一

    /**
     * 文本键：去首尾 W 后 {@code ''} 或 {@code '0'} 按未传（旧 trim + !empty()），{@code 00}、{@code 0,} 等保留为字面值；
     * level 按 intval（0 未传）；year 为 intval 为 0 的写法未传。
     */
    @ParameterizedTest(name = "{0}={1} → {2}")
    @CsvSource(delimiter = '|', value = {
        "area|0|", "area|+0+|", "area|%E3%80%800|", "area|00|00", "area|0%2C|0,", "area|+|",
        "letter|0|", "letter|00|00", "state|0|", "version|0%2C0|0,0", "actor|0|", "director|+0|", "tag|0|",
        "name|0|", "name|00|00", "ids|0|", "ids|00|00", "ids|+0+|",
        "level|0|", "level|00|", "level|0%2C1|", "level|abc|", "level|1%2C2|1", "level|3abc|3", "level|+5+|5",
        "level|1e0|1", "level|%E3%80%805|",
        "year|0|", "year|0000|", "year|0000-0000|", "year|0000~0000|", "year|0000-0001|0000-0001", "year|2020|2020",
        "tid|0|", "tid|00|", "tid|abc|", "tid|0x6|", "tid|-1|-1", "tid|01|1", "tid|1%2C2|1", "tid|6abc|6",
        "tid|1e1|10", "tid|+6|6", "tid|%206|6", "tid|6%20|6",
    })
    void perKeyZeroNormalization(String key, String raw, String expected) {
        for (LegacyUrlStyle style : STYLES) {
            SearchFilters f = filters(style, key + "=" + raw);
            String actual = switch (key) {
                case "level" -> f.level() == null ? null : f.level().toString();
                case "tid" -> f.tid() == null ? null : f.tid().toString();
                default -> f.params().get(key);
            };
            assertThat(actual).as(style + " " + key + "=" + raw).isEqualTo(expected);
        }
    }

    @Test
    void zeroLikeValuesThatContentRejectsAreStill400() {
        for (LegacyUrlStyle style : STYLES) {
            // 旧版按长度既不是 4 也不是 9 时不筛选（如 0,）；新版沿用 1.1.0 起的 400（14 文档第 5.1 节）
            assertThat(invalid(style, "year=0%2C").field()).isEqualTo("year");
            assertThat(invalid(style, "year=00").field()).isEqualTo("year");
            // level 的 intval 不为 0 而越界：旧版 vod_level IN (10)，无结果；新版 400
            assertThat(invalid(style, "level=10").field()).isEqualTo("level");
            assertThat(invalid(style, "level=-1").field()).isEqualTo("level");
        }
    }

    /** tid 的 intval 与 PHP 黄金向量逐项相同；为 0 时未传，负数照常筛选（tid 为负数）。 */
    @Test
    void tidFollowsPhpIntvalVectors() throws Exception {
        JsonNode golden;
        try (InputStream in = getClass().getResourceAsStream("/content/vodtag/php-golden.json")) {
            golden = JSON.readTree(in);
        }
        int n = 0;
        for (JsonNode v : golden.get("intval")) {
            String raw = v.get("input").asString();
            long expected = v.get("intval").asLong();
            String q = "tid=" + enc(raw);
            if (q.length() > 2048) {
                continue;
            }
            SearchFilters f;
            try {
                f = filters(LegacyUrlStyle.REPO_DEFAULT, q);
            } catch (ApiException e) {
                // 表单解码一次后不是合法 UTF-8 的输入不会出现在这里（向量都是合法文本）
                throw new AssertionError(raw, e);
            }
            assertThat(f.tid()).as("tid=" + raw).isEqualTo(expected == 0 ? null : expected);
            assertThat(PhpIntval.of(raw)).isEqualTo(expected);
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(50);
    }

    // ---------------------------------------------------------------- by=rnd

    @Test
    void rndIsRandomSortAndKeepsOtherConditions() {
        for (LegacyUrlStyle style : STYLES) {
            SearchFilters f = filters(style, "by=rnd&letter=A&order=asc");
            assertThat(f.random()).isTrue();
            assertThat(f.sort()).isNull();
            assertThat(f.order()).isEqualTo(SearchFilters.SortOrder.ASC);
            assertThat(f.params()).containsEntry("by", "rnd");
            assertThat(filters(style, "by=+rnd+").random()).as("首尾空白").isTrue();
            assertThat(match(style, "by=rnd").canonical()).isTrue();
            assertThat(f.withSort(VideoSort.HITS).random()).as("换成其他排序时去掉随机").isFalse();
            assertThat(invalid(style, "by=RND").field()).isEqualTo("sort");
            SiteRoute.VodSearch r = (SiteRoute.VodSearch) match(style, "by=rnd&page=3").route();
            assertThat(r.page()).as("算法二使用请求的页码").isEqualTo(3);
        }
        assertThatThrownBy(() -> new SearchFilters(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, VideoSort.TIME, true, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 链接键序、回读与预算

    @Test
    void linkKeysAreSortedLikeMacUrlAndCanonicalOnlyForThatOrder() {
        LegacyUrlBuilder urls = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT);
        SearchFilters f = filters(LegacyUrlStyle.REPO_DEFAULT, "wd=x&tid=6&actor=a&by=rnd&ids=1&year=2020&letter=A"
                + "&version=v&name=n&state=s&tag=t&director=d&level=3&class=c&lang=l&area=r&order=asc");
        String q = urls.vodSearchLink(f, 2).query();
        assertThat(q).isEqualTo("actor=a&area=r&by=rnd&class=c&director=d&ids=1&lang=l&letter=A&level=3&name=n"
                + "&order=asc&page=2&state=s&tag=t&tid=6&version=v&wd=x&year=2020");
        assertThat(LegacyUrlBuilder.SEARCH_LINK_KEYS).isSorted();
        assertThat(match(LegacyUrlStyle.REPO_DEFAULT, q).canonical()).isTrue();
        assertThat(match(LegacyUrlStyle.REPO_DEFAULT, "tid=6&actor=a").canonical()).as("键序不同").isFalse();
        assertThat(match(LegacyUrlStyle.REPO_DEFAULT, "tid=06").canonical()).as("intval 归一").isFalse();
        assertThat(match(LegacyUrlStyle.REPO_DEFAULT, "level=1%2C2").canonical()).isFalse();
        assertThat(match(LegacyUrlStyle.REPO_DEFAULT, "ids=1%2C2").canonical()).as("逗号统一编码").isTrue();
    }

    @Test
    void randomConditionsWithNewKeysRoundTrip() {
        Random rnd = new Random(20261017L);
        String alphabet = "aZ09中文 /\\.%+&=?#!_~*'(),-，　";
        String[] keys = {"wd", "area", "lang", "class", "letter", "state", "version", "actor", "director", "tag",
            "name"};
        int[] max = {64, 20, 10, 32, 64, 64, 64, 64, 64, 64, 64};
        int accepted = 0;
        for (int i = 0; i < 3000; i++) {
            SearchFilters f = SearchFilters.NONE;
            try {
                for (int k = 0; k < keys.length; k++) {
                    if (rnd.nextInt(4) == 0) {
                        f = f.with(keys[k], text(rnd, alphabet, 1 + rnd.nextInt(Math.min(max[k], 12))));
                    }
                }
                if (rnd.nextBoolean()) {
                    f = f.with("ids", rnd.nextBoolean() ? "all" : rnd.nextInt(100) + ",," + rnd.nextInt(5));
                }
                Long tid = rnd.nextInt(3) == 0 ? (long) (rnd.nextInt(200) - 100) : null;
                if (tid != null && tid == 0) {
                    tid = null;
                }
                boolean random = rnd.nextInt(5) == 0;
                VideoSort sort = random || rnd.nextBoolean() ? null : VideoSort.values()[rnd.nextInt(VideoSort.values().length)];
                f = new SearchFilters(f.keyword(), f.area(), f.lang(), f.year(), rnd.nextBoolean() ? null : 1 + rnd.nextInt(9),
                        f.classes(), f.letter(), f.state(), f.version(), f.actor(), f.director(), f.tag(), f.name(),
                        f.ids(), tid, sort, random, rnd.nextBoolean() ? null : SearchFilters.SortOrder.ASC);
            } catch (IllegalArgumentException notFixedPoint) {
                continue;
            }
            LegacyUrlStyle style = STYLES.get(rnd.nextInt(STYLES.size()));
            int page = 1 + rnd.nextInt(1000);
            LegacyUrlBuilder urls = new LegacyUrlBuilder(style);
            SiteLink link = urls.vodSearchLink(f, page);
            LegacyUrlParser.Match m = parser(style).parseSearch("/" + link.path(), link.query()).orElseThrow();
            assertThat(m.route()).as(link.relative()).isEqualTo(new SiteRoute.VodSearch(f, page));
            assertThat(m.canonical()).isTrue();
            SiteSettings s = SiteSettings.from(new SiteProperties("true", "合成站",
                    "https://maccms.local.test/" + "a".repeat(741) + "/", "", "", "", "", "open", "", "false", "false"));
            assertThat(s.canonical(link).href()).hasSizeLessThanOrEqualTo(SafeUrl.MAX_LENGTH);
            accepted++;
        }
        assertThat(accepted).isGreaterThan(1000);
    }

    /**
     * 新键与 MACC-20 的键共用同一个查询串预算（{@link LegacyUrlBuilder#MAX_SEARCH_LINK_QUERY}，含 page=1000）：
     * 每个值都合法、但编码后合计超出预算的组合 → 400"搜索条件过长"（不回显），生成的链接因此总在 SafeUrl 上限内。
     */
    @Test
    void combinationsBeyondTheLinkBudgetAre400() {
        String han = enc("汉".repeat(64));
        for (LegacyUrlStyle style : STYLES) {
            ApiException.FieldError e = invalid(style, "wd=" + han + "&actor=" + han + "&tag=" + han);
            assertThat(e.field()).isEqualTo("query");
            assertThat(e.message()).isEqualTo("搜索条件过长");
            // 预算内的上限组合仍可接受（MACC-20 的四项上限组合不受影响）
            SearchFilters ok = filters(style, "wd=" + han + "&area=" + enc("汉".repeat(20)) + "&lang="
                    + enc("汉".repeat(10)) + "&class=" + enc("汉".repeat(32)) + "&year=2020&level=9&by=hits_month&order=desc");
            assertThat(new LegacyUrlBuilder(style).vodSearchLink(ok, 1000).query().length())
                    .isLessThanOrEqualTo(LegacyUrlBuilder.MAX_SEARCH_LINK_QUERY);
        }
        assertThatThrownBy(() -> SearchFilters.NONE.with("wd", "汉".repeat(64)).with("actor", "汉".repeat(64))
                .with("tag", "汉".repeat(64))).isInstanceOf(IllegalArgumentException.class).hasMessage("搜索条件过长");
    }

    @Test
    void invariantsRejectNonFixedPoints() {
        assertThatThrownBy(() -> SearchFilters.NONE.with("letter", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchFilters.NONE.with("ids", " 1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchFilters.NONE.with("year", "0000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchFilters.NONE.with("year", "0000-0000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchFilters(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, 0L, null, false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SearchFilters.NONE.with("year", "0000-0001").year()).isEqualTo("0000-0001");
        List<String> keys = new ArrayList<>(SearchFilters.NONE.with("tag", "x").with("letter", "A").params().keySet());
        assertThat(keys).containsExactly("letter", "tag");
    }

    private static String text(Random rnd, String alphabet, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}

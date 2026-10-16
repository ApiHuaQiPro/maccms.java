package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.content.application.VideoSort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 搜索链接的生成与回读（09 方案第 7.2.2、7.2.4、7.2.5、7.2.7 节）：对 SSR 能接受的任意 {@code (F, page)}，
 * {@code parseSearch("/" + B, vodSearchLink(F, page).query())} 返回同一 {@code (F, page)} 且 canonical；分页、移除、组合、
 * 排序与 canonical 链接都能构造 SafeUrl，长度在 {@code B?Q ≤ 1280}、canonical ≤ 2048 之内。
 * 四种 URL 形态 × 三种完整 base-url（根地址、含子目录、总长恰为 768 的子目录地址）。
 */
class SearchLinkRoundTripTest {

    private static final List<LegacyUrlStyle> STYLES = LegacyUrlParserSearchTest.STYLES;

    /** 完整 base-url（协议、主机、端口、路径合计）：根、子目录、总长恰为 768 的子目录。 */
    private static final List<String> BASE_URLS = List.of("http://maccms.local.test/", "https://maccms.local.test/sub/",
            "https://maccms.local.test/" + "a".repeat(741) + "/");

    private static SiteSettings settings(String baseUrl) {
        return SiteSettings.from(new SiteProperties("true", "合成站", baseUrl, "", "", "", "", "open", "", "false",
                "false"));
    }

    /** 第 7.2.7 节向量中被接受的条件，以及边界组合。 */
    private static List<SearchFilters> samples() {
        List<SearchFilters> out = new ArrayList<>();
        for (String wd : List.of("A/B", "A\\B", ".", "..", "A.html", "a.htmlb", "%41", "%2F", "C++", "C", "a b", "中文",
                "Love, Death & Robots", "a=b&c", "!%_", "~*'()", "汉".repeat(64), "a.html.html", "?#", "x/../y")) {
            out.add(SearchFilters.NONE.with("wd", wd));
        }
        out.add(SearchFilters.NONE.with("area", "..").with("wd", "x"));
        out.add(SearchFilters.NONE.with("area", "大陆").with("lang", "国语").with("year", "2020"));
        out.add(new SearchFilters(null, null, null, null, 9, null, null, null));
        out.add(new SearchFilters(null, null, null, null, null, null, VideoSort.HITS, SearchFilters.SortOrder.ASC));
        out.add(new SearchFilters(null, "a/b", "%2E", "2026", 1, "剧情/动作", VideoSort.TIME_ADD,
                SearchFilters.SortOrder.DESC));
        out.add(maximum());
        out.add(SearchFilters.NONE);
        return out;
    }

    private static SearchFilters maximum() {
        return new SearchFilters("汉".repeat(64), "汉".repeat(20), "汉".repeat(10), "2020", 9, "汉".repeat(32),
                VideoSort.HITS_MONTH, SearchFilters.SortOrder.DESC);
    }

    private static void assertRoundTrip(LegacyUrlStyle style, SearchFilters f, int page) {
        LegacyUrlBuilder urls = new LegacyUrlBuilder(style);
        SiteLink link = urls.vodSearchLink(f, page);
        assertThat(link.path()).isEqualTo(urls.vodSearch(Map.of(), 1));
        assertThat(link.relative().length()).isLessThanOrEqualTo(SitePaths.MAX_LENGTH);
        assertThat(link.query()).matches("[A-Za-z0-9._+=&%-]*").doesNotContain("%2e").doesNotContainPattern("%[0-9A-F]?[a-f]");
        LegacyUrlParser.Match m = new LegacyUrlParser(urls).parseSearch("/" + link.path(), link.query()).orElseThrow();
        assertThat(m.route()).as(style + " " + link.relative()).isEqualTo(new SiteRoute.VodSearch(f, page));
        assertThat(m.canonical()).as(link.relative()).isTrue();
        assertThat(m.path()).isEqualTo(link.path());
        for (String base : BASE_URLS) {
            SiteSettings s = settings(base);
            assertThat(s.canonical(link).href()).isEqualTo(base + link.relative())
                    .hasSizeLessThanOrEqualTo(SafeUrl.MAX_LENGTH);
            assertThat(s.publicPath(link).href()).isEqualTo(s.homePath().href() + link.relative());
        }
    }

    @Test
    void everyAcceptedConditionRoundTripsForEveryLinkKind() {
        int checked = 0;
        for (LegacyUrlStyle style : STYLES) {
            for (SearchFilters f : samples()) {
                for (int page : new int[] {1, 2, 1000}) {
                    assertRoundTrip(style, f, page);
                    checked++;
                }
                // 移除每个键
                for (String key : List.of("wd", "area", "lang", "year", "class")) {
                    assertRoundTrip(style, f.with(key, null), 1);
                }
                // 组合 / 替换每个候选值
                for (String[] c : new String[][] {{"area", "香港"}, {"lang", "粤语"}, {"year", "1999"},
                        {"class", "喜剧"}, {"wd", "新/关键字"}}) {
                    assertRoundTrip(style, f.with(c[0], c[1]), 1);
                }
                // 排序
                for (VideoSort s : VideoSort.values()) {
                    assertRoundTrip(style, f.withSort(s), 1);
                }
                assertRoundTrip(style, f.withSort(null), 1);
            }
        }
        assertThat(checked).isEqualTo(STYLES.size() * samples().size() * 3);
    }

    @Test
    void generatedLinksAreFixedPathPlusSortedQuery() {
        LegacyUrlBuilder urls = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT);
        assertThat(urls.vodSearchLink(SearchFilters.NONE, 1).relative()).isEqualTo("index.php/vod/search.html");
        assertThat(urls.vodSearchLink(SearchFilters.NONE, 2).relative()).isEqualTo("index.php/vod/search.html?page=2");
        SearchFilters f = new SearchFilters("C++ a/b", "大陆", null, "2020", 9, null, VideoSort.HITS,
                SearchFilters.SortOrder.ASC);
        assertThat(urls.vodSearchLink(f, 3).relative()).isEqualTo("index.php/vod/search.html?area=%E5%A4%A7%E9%99%86"
                + "&by=hits&level=9&order=asc&page=3&wd=C%2B%2B+a%2Fb&year=2020");
        assertThat(urls.vodSearchLink(SearchFilters.NONE.with("wd", "a.html\\..%"), 1).query())
                .isEqualTo("wd=a.html%5C..%25");
        // 四种形态下的搜索基址 B（都是现有 vodSearch(Map.of(), 1) 的输出）
        List<String> bases = STYLES.stream().map(s -> new LegacyUrlBuilder(s).vodSearchLink(f, 1).path()).toList();
        assertThat(bases).containsExactly("index.php/vod/search.html", "index.php/vod/search/", "vod/search.html",
                "vod/search/");
        // suffixHide 不改写查询串中的 .html
        assertThat(new LegacyUrlBuilder(STYLES.get(1)).vodSearchLink(SearchFilters.NONE.with("wd", "a.htmlb"), 1)
                .relative()).isEqualTo("index.php/vod/search/?wd=a.htmlb");
    }

    @Test
    void maximumCombinationLengths() {
        SearchFilters f = maximum();
        List<Integer> sitePaths = new ArrayList<>();
        List<Integer> canonicals = new ArrayList<>();
        List<Integer> pageLinks = new ArrayList<>();
        SiteSettings longest = settings(BASE_URLS.get(2));
        assertThat(longest.baseUrl().href()).hasSize(768);
        assertThat(longest.homePath().href()).hasSize(743);
        for (LegacyUrlStyle style : STYLES) {
            SiteLink link = new LegacyUrlBuilder(style).vodSearchLink(f, 1000);
            assertThat(link.query()).hasSize(LegacyUrlBuilder.MAX_SEARCH_LINK_QUERY);
            sitePaths.add(link.relative().length());
            canonicals.add(longest.canonical(link).href().length());
            pageLinks.add(longest.publicPath(link).href().length());
        }
        assertThat(sitePaths).containsExactly(1235, 1231, 1225, 1221);
        assertThat(canonicals).containsExactly(2003, 1999, 1993, 1989);
        assertThat(pageLinks).containsExactly(1978, 1974, 1968, 1964);
        assertThat(settings(BASE_URLS.get(0)).canonical(new LegacyUrlBuilder(STYLES.get(0)).vodSearchLink(f, 1000))
                .href()).hasSize(1260);
    }

    /** 以固定种子随机生成满足第 4 节字母表、且是归一不动点的条件，检查同样的不变式。 */
    @Test
    void randomConditionsWithFixedSeedRoundTrip() {
        Random rnd = new Random(20261016L);
        String alphabet = "aZ09 ._-+/\\%&=?#!*'()~,:;@$[]{}|^`\"<>中文汉字、，。　 ·é";
        int accepted = 0;
        for (int i = 0; i < 2000; i++) {
            String wd = randomText(rnd, alphabet, 1 + rnd.nextInt(64));
            String area = rnd.nextBoolean() ? null : randomText(rnd, alphabet.replace(",", ""), 1 + rnd.nextInt(20));
            String lang = rnd.nextBoolean() ? null : randomText(rnd, alphabet.replace(",", ""), 1 + rnd.nextInt(10));
            String cls = rnd.nextBoolean() ? null : randomText(rnd, alphabet.replace(",", ""), 1 + rnd.nextInt(32));
            String year = rnd.nextBoolean() ? null : String.format("%04d", rnd.nextInt(10000));
            Integer level = rnd.nextBoolean() ? null : 1 + rnd.nextInt(9);
            VideoSort sort = rnd.nextBoolean() ? null : VideoSort.values()[rnd.nextInt(VideoSort.values().length)];
            SearchFilters.SortOrder order = rnd.nextBoolean() ? null
                    : SearchFilters.SortOrder.values()[rnd.nextInt(2)];
            SearchFilters f;
            try {
                f = new SearchFilters(wd, area, lang, year, level, cls, sort, order);
            } catch (IllegalArgumentException e) {
                // 首尾空白、'0' 等不是归一不动点的组合：SSR 不会产生，跳过
                continue;
            }
            accepted++;
            assertRoundTrip(STYLES.get(rnd.nextInt(STYLES.size())), f, 1 + rnd.nextInt(1000));
        }
        assertThat(accepted).isGreaterThan(1000);
    }

    private static String randomText(Random rnd, String alphabet, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @Test
    void filtersRejectValuesThatWouldNotRoundTrip() {
        for (String v : List.of("", "0", " a", "a ", "　a", "a ", "﻿a")) {
            assertThatThrownBy(() -> SearchFilters.NONE.with("wd", v)).as("[" + v + "]")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SearchFilters.NONE.with("area", "a,b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchFilters.NONE.with("year", "20")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchFilters.NONE.with("wd", "x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("搜索条件不合法");
        assertThatThrownBy(() -> new SearchFilters(null, null, null, null, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchFilters.NONE.with("tag", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatorRejectsUnsupportedStylesAndPages() {
        assertThatThrownBy(() -> new LegacyUrlBuilder(new LegacyUrlStyle(false, false, true, '/'))
                .vodSearchLink(SearchFilters.NONE, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LegacyUrlBuilder(new LegacyUrlStyle(false, false, false, '-'))
                .vodSearchLink(SearchFilters.NONE, 1)).isInstanceOf(IllegalStateException.class);
        LegacyUrlBuilder urls = new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT);
        assertThatThrownBy(() -> urls.vodSearchLink(SearchFilters.NONE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> urls.vodSearchLink(SearchFilters.NONE, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void siteLinkValidatesPathQueryAndTotalLength() {
        assertThat(new SiteLink("index.php/vod/search.html", "").relative()).isEqualTo("index.php/vod/search.html");
        assertThatThrownBy(() -> new SiteLink("index.php/vod/search.html", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteLink("/index.php", "wd=x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteLink("index.php/vod/../search.html", "wd=x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteLink("index.php/vod/search.html", "wd=a?b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("站内查询串不合法");
        // B?Q 与站内路径共用 1280 的预算
        String b = "index.php/vod/search.html";
        String fits = "wd=" + "a".repeat(SitePaths.MAX_LENGTH - b.length() - 4);
        assertThat(new SiteLink(b, fits).relative()).hasSize(SitePaths.MAX_LENGTH);
        assertThatThrownBy(() -> new SiteLink(b, fits + "a")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("站内地址不合法");
    }
}

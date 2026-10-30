package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.application.LegacyMultiValue.PlayEpisode;
import com.maccms.backend.content.application.LegacyMultiValue.PlaySource;
import com.maccms.backend.content.application.view.ArticleContentPageDto;
import com.maccms.backend.content.application.view.PlotDto;
import com.maccms.backend.content.application.view.ScreenshotDto;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用 PHP 黄金向量（src/test/resources/content/multivalue/php-vectors.json，由 src/test/php/content_multivalue_golden.php
 * 执行旧函数生成，PHP 7.3/8.0/8.2 输出相同）锁定 {@link LegacyMultiValue}。
 * 对照规则：PHP 的 null（取不到的数组元素）对应 Java 的 ""、缺失的配置项对应空 Map；PHP 数组键对应 sid/nid/page/index；
 * 截图名称在 PHP 中可能是整数序号，按字符串比较。除此以外逐字一致。
 */
class LegacyMultiValueGoldenTest {

    private static JsonNode vectors;
    private static Map<String, Map<String, String>> players;
    private static Map<String, Map<String, String>> downers;
    private static Map<String, Map<String, String>> servers;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LegacyMultiValueGoldenTest.class.getResourceAsStream("/content/multivalue/php-vectors.json")) {
            vectors = JsonMapper.builder().build().readTree(in);
        }
        players = config("vodplayer");
        downers = config("voddowner");
        servers = config("vodserver");
    }

    /** 配置值是 PHP 标量（var_export 中可能是字符串或整数），交给 Java 时按 PHP 字符串转换。 */
    private static Map<String, Map<String, String>> config(String name) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        vectors.get("config").get(name).properties().forEach(e -> {
            Map<String, String> entry = new LinkedHashMap<>();
            e.getValue().properties().forEach(f -> entry.put(f.getKey(), f.getValue().asString()));
            out.put(e.getKey(), entry);
        });
        return out;
    }

    private static String str(JsonNode n) {
        return n == null || n.isNull() ? "" : n.asString();
    }

    private static Stream<Arguments> section(String name) {
        List<Arguments> out = new ArrayList<>();
        vectors.get(name).forEach(v -> out.add(Arguments.of(v.get("id").asString(), v)));
        return out.stream();
    }

    static Stream<Arguments> playListOne() {
        return section("playListOne");
    }

    static Stream<Arguments> artList() {
        return section("artList");
    }

    static Stream<Arguments> plotList() {
        return section("plotList");
    }

    static Stream<Arguments> screenshotList() {
        return section("screenshotList");
    }

    static Stream<Arguments> playList() {
        List<Arguments> out = new ArrayList<>();
        for (String branch : List.of("sort1", "sort0")) {
            vectors.get("playList").get(branch).forEach(v ->
                    out.add(Arguments.of(branch + "/" + v.get("id").asString(), branch.equals("sort1"), v)));
        }
        return out.stream();
    }

    private static List<List<Object>> episodes(JsonNode out) {
        List<List<Object>> rows = new ArrayList<>();
        out.forEach(e -> {
            JsonNode v = e.get("value");
            assertThat(v.get("nid").asInt()).isEqualTo(e.get("key").asInt());
            rows.add(List.of(v.get("nid").asInt(), str(v.get("name")), str(v.get("url")), str(v.get("from"))));
        });
        return rows;
    }

    private static List<List<Object>> episodes(List<PlayEpisode> list) {
        return list.stream().map(e -> List.<Object>of(e.nid(), e.name(), e.url(), e.from())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playListOne")
    void playListOneMatchesPhp(String id, JsonNode v) {
        String server = v.get("serverOne").isNull() ? "" : v.get("serverOne").asString();
        List<PlayEpisode> actual = LegacyMultiValue.playEpisodes(v.get("urlOne").asString(), v.get("fromOne").asString(),
                server);
        assertThat(episodes(actual)).isEqualTo(episodes(v.get("out")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playList")
    void playListMatchesPhp(String id, boolean sort, JsonNode v) {
        Map<String, Map<String, String>> config = v.get("flag").asString().equals("play") ? players : downers;
        List<PlaySource> actual = LegacyMultiValue.playSources(v.get("from").asString(), v.get("url").asString(),
                v.get("server").asString(), v.get("note").asString(), config, servers, sort);

        List<List<Object>> expected = new ArrayList<>();
        v.get("out").forEach(e -> {
            JsonNode s = e.get("value");
            assertThat(s.get("sid").asInt()).isEqualTo(e.get("key").asInt());
            assertThat(s.get("url_count").asInt()).isEqualTo(s.get("urls").size());
            expected.add(List.of(s.get("sid").asInt(), str(s.get("from")), str(s.get("url")), str(s.get("server")),
                    str(s.get("note")), configEntry(s.get("player_info")), configEntry(s.get("server_info")),
                    episodes(s.get("urls"))));
        });
        List<List<Object>> got = actual.stream().map(s -> List.<Object>of(s.sid(), s.from(), s.url(), s.server(),
                s.note(), s.player(), s.serverInfo(), episodes(s.episodes()))).toList();
        assertThat(got).isEqualTo(expected);
        assertThat(actual).allSatisfy(s -> assertThat(s.urlCount()).isEqualTo(s.episodes().size()));
    }

    private static Map<String, String> configEntry(JsonNode n) {
        Map<String, String> m = new LinkedHashMap<>();
        if (n != null && !n.isNull()) {
            n.properties().forEach(f -> m.put(f.getKey(), f.getValue().asString()));
        }
        return Map.copyOf(m);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("artList")
    void artListMatchesPhp(String id, JsonNode v) {
        List<ArticleContentPageDto> actual = LegacyMultiValue.articlePages(v.get("title").asString(), v.get("note").asString(),
                v.get("content").asString());
        List<ArticleContentPageDto> expected = new ArrayList<>();
        v.get("out").forEach(e -> {
            JsonNode p = e.get("value");
            assertThat(p.get("page").asInt()).isEqualTo(e.get("key").asInt());
            expected.add(new ArticleContentPageDto(p.get("page").asInt(), str(p.get("title")), str(p.get("note")),
                    str(p.get("content"))));
        });
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plotList")
    void plotListMatchesPhp(String id, JsonNode v) {
        List<PlotDto> actual = LegacyMultiValue.plots(v.get("name").asString(), v.get("detail").asString());
        List<PlotDto> expected = new ArrayList<>();
        v.get("out").forEach(e -> expected.add(new PlotDto(e.get("key").asInt(), str(e.get("value").get("name")),
                str(e.get("value").get("detail")))));
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("screenshotList")
    void screenshotListMatchesPhp(String id, JsonNode v) {
        List<ScreenshotDto> actual = LegacyMultiValue.screenshots(v.get("raw").asString());
        List<ScreenshotDto> expected = new ArrayList<>();
        v.get("out").forEach(e -> expected.add(new ScreenshotDto(e.get("key").asInt(), str(e.get("value").get("name")),
                str(e.get("value").get("url")))));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void everySectionHasVectors() {
        for (String s : List.of("playListOne", "artList", "plotList", "screenshotList")) {
            assertThat(vectors.get(s).size()).as(s).isGreaterThan(5);
        }
        assertThat(vectors.get("playList").get("sort1").size()).isEqualTo(vectors.get("playList").get("sort0").size());
    }

    @Test
    void emptyInputsYieldEmptyLists() {
        assertThat(LegacyMultiValue.playSources(null, null, null, null, players, servers, true)).isEmpty();
        assertThat(LegacyMultiValue.playEpisodes(null, null, null)).isEmpty();
        assertThat(LegacyMultiValue.articlePages(null, null, null)).isEmpty();
        assertThat(LegacyMultiValue.plots(null, "x")).isEmpty();
        assertThat(LegacyMultiValue.screenshots(null)).isEmpty();
        assertThat(LegacyMultiValue.screenshots("0")).isEmpty();
    }

    @Test
    void phpLooseComparisons() {
        assertThat(PhpValues.looseEqualsString("1", "1")).isTrue();
        assertThat(PhpValues.looseEqualsString("01", "1")).isTrue();
        assertThat(PhpValues.looseEqualsString("1.0", "1")).isTrue();
        assertThat(PhpValues.looseEqualsString("1a", "1")).isFalse();
        assertThat(PhpValues.looseEqualsString(null, "1")).isFalse();
        assertThat(PhpValues.compareRegular("10", "9")).isPositive();
        assertThat(PhpValues.compareRegular("abc", "10")).isPositive();
        assertThat(PhpValues.compareRegular(null, "10")).isNegative();
        assertThat(PhpValues.compareRegular("30", "30.0")).isZero();
        assertThat(PhpValues.compareRegular("1e9999999999", "5")).isPositive(); // PHP 中为 INF
        assertThat(PhpValues.compareRegular("9223372036854775808", "9223372036854775807")).isGreaterThanOrEqualTo(0);
    }

    /** 数字与非数字 sort 混用时比较不满足传递性；排序不得抛异常（旧版不会出错），组数多于 TimSort 的阈值也一样。 */
    @Test
    void nonTransitiveSortValuesNeverThrow() {
        Map<String, Map<String, String>> config = new LinkedHashMap<>();
        StringBuilder from = new StringBuilder();
        StringBuilder url = new StringBuilder();
        String[] sorts = {"9", "10", "1a"};
        for (int i = 0; i < 60; i++) {
            config.put("p" + i, Map.of("status", "1", "sort", sorts[i % 3]));
            from.append(i == 0 ? "" : "$$$").append("p").append(i);
            url.append(i == 0 ? "" : "$$$").append("1$u").append(i);
        }
        assertThat(LegacyMultiValue.playSources(from.toString(), url.toString(), "", "", config, Map.of(), true))
                .hasSize(60);
    }
}

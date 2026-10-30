package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.application.VideoFilter;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 固定旧 URL 到新接口查询参数的映射（14 文档第 2 节），证据是 PHP 黄金样本（vod_list_golden.php 的 urls、substring 部分，
 * 旧代码 App::routeCheck + mac_param_url + mac_search_len_check 的实际输出，PHP 8.0 与 7.3 各一份）：
 * <ul>
 *   <li>位置参数地址 vodsearch（14 个位置）与 vodshow（12 个位置）每个位置对应的旧参数，空位即未传；</li>
 *   <li>每个旧参数对应的新参数名；</li>
 *   <li>search_len 截断只作用于名单中的键，ids、year 不截断；截断函数 mac_substring 的结果随 PHP 版本变化。</li>
 * </ul>
 * 只读 JSON，不连数据库；JSON 由 VideoListGoldenMySqlIT 所用的同一次生成产生。
 */
class LegacyVodUrlMappingTest {

    /** application/route.php:331 的位置顺序。 */
    static final List<String> SEARCH_SLOTS = List.of("wd", "actor", "area", "by", "class", "director", "lang", "letter",
            "level", "order", "page", "state", "tag", "year");
    /** application/route.php:321 的位置顺序（第 1 位 id 是分类 ID）。 */
    static final List<String> SHOW_SLOTS = List.of("id", "area", "by", "class", "lang", "letter", "level", "order", "page",
            "state", "tag", "year");
    /** 旧 URL 参数 → 新接口参数（vod/search 与 vod/show 可达的全部列表参数）。 */
    static final Map<String, String> LEGACY_TO_API = new LinkedHashMap<>();

    static {
        LEGACY_TO_API.put("wd", "keyword");
        LEGACY_TO_API.put("class", "classes");
        LEGACY_TO_API.put("by", "sort");
        for (String same : List.of("actor", "area", "director", "lang", "letter", "level", "order", "page", "state", "tag",
                "year", "ids", "name", "version")) {
            LEGACY_TO_API.put(same, same);
        }
    }

    /** mac_search_len_check 的名单（application/common.php:2881），与 URL 列表参数的交集。 */
    static final Set<String> TRUNCATED = Set.of("wd", "tag", "class", "letter", "name", "state", "level", "area", "lang",
            "version", "actor", "director");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode php80;
    private static JsonNode php73;

    @BeforeAll
    static void load() throws Exception {
        php80 = read("php-golden.json");
        php73 = read("php73-golden.json");
        assertThat(php80.get("php").asString()).startsWith("8.0");
        assertThat(php73.get("php").asString()).startsWith("7.3");
        assertThat(php80.get("searchLen").asInt()).isEqualTo(10);
    }

    private static JsonNode read(String name) throws Exception {
        try (InputStream in = LegacyVodUrlMappingTest.class.getResourceAsStream("/content/vodlist/" + name)) {
            return JSON.readTree(in);
        }
    }

    private static JsonNode url(JsonNode golden, String id) {
        for (JsonNode u : golden.get("urls")) {
            if (u.get("id").asString().equals(id)) {
                return u;
            }
        }
        throw new AssertionError("黄金样本缺少 " + id);
    }

    private static Map<String, String> params(JsonNode u, String field) {
        Map<String, String> out = new TreeMap<>();
        u.get(field).properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return out;
    }

    @Test
    void everySearchSlotMapsToItsLegacyParameterAndEmptySlotsAreAbsent() {
        for (JsonNode g : List.of(php80, php73)) {
            for (int i = 0; i < SEARCH_SLOTS.size(); i++) {
                String key = SEARCH_SLOTS.get(i);
                JsonNode u = url(g, "vodsearch.slot" + (i + 1) + "." + key);
                assertThat(u.get("dispatch").asString()).isEqualTo("vod/search");
                Map<String, String> p = params(u, "param");
                String sample = url(g, "vodsearch.full").get("param").get(key).asString();
                Map<String, String> expected = new TreeMap<>(Map.of("page", key.equals("page") ? sample : "1"));
                expected.put(key, sample);
                assertThat(p).as(u.get("pathInfo").asString()).isEqualTo(expected);
            }
            assertThat(params(url(g, "vodsearch.full"), "param").keySet()).containsExactlyInAnyOrderElementsOf(SEARCH_SLOTS);
        }
    }

    @Test
    void everyShowSlotMapsToItsLegacyParameter() {
        for (JsonNode g : List.of(php80, php73)) {
            for (int i = 1; i < SHOW_SLOTS.size(); i++) {
                String key = SHOW_SLOTS.get(i);
                JsonNode u = url(g, "vodshow.slot" + (i + 1) + "." + key);
                assertThat(u.get("dispatch").asString()).isEqualTo("vod/show");
                Map<String, String> p = params(u, "param");
                assertThat(p).as(u.get("pathInfo").asString()).containsEntry("id", "1").containsKey(key).hasSize(
                        key.equals("page") ? 2 : 3);
            }
            assertThat(params(url(g, "vodshow.full"), "param").keySet()).containsExactlyInAnyOrderElementsOf(SHOW_SLOTS);
        }
    }

    @Test
    void positionalUrlsCannotCarryHyphensSoYearRangesShiftTheSlots() {
        // 值中的 '-' 会被当作分隔符：wd=a-b 时多出的段并入 wd；year=2018-2020 写在最后一位时，前一位 tag 得到 2018
        assertThat(params(url(php80, "vodsearch.dash-in-value"), "param")).containsEntry("wd", "a-b");
        assertThat(params(url(php80, "vodsearch.year-range"), "param")).containsEntry("wd", "-")
                .containsEntry("tag", "2018").containsEntry("year", "2020");
        // '0' 在路由与 mac_param_url 阶段保留，到 listCacheData 才因 empty('0') 被丢弃（ids.0、letter.0 用例）
        assertThat(params(url(php80, "vodsearch.zero"), "param")).containsEntry("wd", "0").containsEntry("actor", "0")
                .containsEntry("letter", "0").containsEntry("tag", "0");
    }

    @Test
    void everyLegacyListParameterHasAnApiParameter() {
        Set<String> api = new java.util.HashSet<>(VideoFilter.PARAMETERS);
        api.addAll(List.of("categoryId", "sort", "order", "page", "pageSize"));
        for (String legacy : SEARCH_SLOTS) {
            assertThat(LEGACY_TO_API).as(legacy).containsKey(legacy);
        }
        LEGACY_TO_API.values().forEach(v -> assertThat(api).as(v).contains(v));
        assertThat(LEGACY_TO_API).doesNotContainKeys("isend", "timeadd", "tid");
    }

    @Test
    void searchLenTruncatesOnlyTheWhitelistedKeysAndNeverIdsOrYear() {
        for (JsonNode g : List.of(php80, php73)) {
            JsonNode u = url(g, "query.truncation");
            Map<String, String> before = params(u, "param");
            Map<String, String> after = params(u, "afterLenCheck");
            for (Map.Entry<String, String> e : before.entrySet()) {
                String k = e.getKey();
                if (k.equals("page")) {
                    continue;
                }
                int len = e.getValue().codePointCount(0, e.getValue().length());
                if (TRUNCATED.contains(k)) {
                    assertThat(len).as(k + " 样本长度").isGreaterThan(10).isLessThanOrEqualTo(VideoFilter.MAX_LIST_LENGTH);
                    assertThat(after.get(k).codePointCount(0, after.get(k).length())).as(k + " 截断为 10 个字符")
                            .isEqualTo(10);
                } else {
                    assertThat(after.get(k)).as(k + " 不截断").isEqualTo(e.getValue());
                }
            }
            assertThat(before.keySet()).contains("ids", "year");
            assertThat(TRUNCATED).doesNotContain("ids", "year");
            assertThat(after.get("ids")).as("17 个字符的 ids 未截断").isEqualTo("1,2,3,4,5,6,7,8,9");
            assertThat(after.get("year")).isEqualTo("2018-2020");
        }
    }

    @Test
    void macSubstringTurnsAsciiLettersIntoSpacesOnPhp8Only() {
        // application/common.php:646-647：substr('00000000 '.$a, -8) 对 7 位二进制（ord 64..127）留下首个空格，' ' == 0
        // 在 PHP 7 为真、PHP 8 为假，于是 PHP 8 把字母等替换成空格；数字、标点（ord < 64）与多字节字符不受影响
        Map<String, String[]> expected = Map.of(
                "abcdefghijkl", new String[]{"          ", "abcdefghij"},
                "A,B,C,D,E,F", new String[]{" , , , , ,", "A,B,C,D,E,"},
                "Tom &amp; Jerry", new String[]{"    &   ; ", "Tom &amp; "},
                "a1b2c3d4e5f6", new String[]{" 1 2 3 4 5", "a1b2c3d4e5"},
                "0123456789ab", new String[]{"0123456789", "0123456789"},
                "演员甲演员乙演员丙演员丁", new String[]{"演员甲演员乙演员丙演", "演员甲演员乙演员丙演"});
        for (int v = 0; v < 2; v++) {
            JsonNode g = v == 0 ? php80 : php73;
            assertThat(g.get("substring").size()).isEqualTo(expected.size());
            for (JsonNode s : g.get("substring")) {
                assertThat(s.get("output").asString()).as(g.get("php") + " " + s.get("input"))
                        .isEqualTo(expected.get(s.get("input").asString())[v]);
            }
        }
    }

    @Test
    void queryStringDecodingDiffersPerKey() {
        Map<String, String> p = params(url(php80, "query.decode"), "param");
        assertThat(p).containsEntry("actor", "甲").as("actor 经 urldecode 再解一次")
                .containsEntry("letter", "%41").as("letter 不经 urldecode（application/common.php:1588）")
                .containsEntry("ids", "1,2").containsEntry("name", "a b").containsEntry("state", "s");
    }

    @Test
    void equalCasesAreIdenticalOnPhp80AndPhp73() {
        Map<String, JsonNode> a = new LinkedHashMap<>();
        php80.get("cases").forEach(c -> a.put(c.get("id").asString(), c));
        int compared = 0;
        for (JsonNode c : php73.get("cases")) {
            JsonNode x = a.get(c.get("id").asString());
            assertThat(x).isNotNull();
            if (c.get("compare").asString().equals("equal")) {
                assertThat(c.get("ids")).as(c.get("id").asString()).isEqualTo(x.get("ids"));
                assertThat(c.get("total")).isEqualTo(x.get("total"));
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(50);
    }
}

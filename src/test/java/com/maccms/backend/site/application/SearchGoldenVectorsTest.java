package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 搜索页"固定路径 + 查询串"与旧 PHP 的对照（PHP 黄金向量中 {@code search.q.*}，由 src/test/php/mac_url_golden.php 执行
 * App::routeCheck + mac_param_url 得到，不连库、不发请求）。规则（09 方案第 8 节 T3）：
 * <ul>
 * <li>Java 认得的每条向量，旧代码也调度到 {@code vod/search}（控制器存在）；</li>
 * <li>值不含 {@code % + & < > " '} 时，{@code wd/area/lang/year/level/class/by/order/page} 的取值一致（Java 未设置 ⇔ PHP 为
 * {@code ''} 或 {@code '0'}，对应旧 {@code !empty()}）；</li>
 * <li>含这些字符的值两边不同（PHP 再 urldecode 一次并做实体编码），逐条写明，作为与旧版的差异；</li>
 * <li>route_status=1（旧站 404）与 pathinfo_depr=- 两个分支 Java 不承接（交 PHP）。</li>
 * </ul>
 * Java 侧的原始请求按浏览器方式构造：路径 = /index.php + PATH_INFO，查询串 = 各 GET 值按表单规则编码一次。
 */
class SearchGoldenVectorsTest {

    private static JsonNode vectors;

    /** 已知差异：向量 id → [Java 的 wd, PHP 的 wd]。 */
    private static final Map<String, String[]> DIFFERENCES = Map.of(
            "search.q.pct", new String[] {"%41", "A"},           // PHP 二次解码
            "search.q.plus", new String[] {"C++", "C  "},         // PHP 二次解码：+ 变空格
            "search.q.amp", new String[] {"Tom & Jerry", "Tom &amp; Jerry"}); // PHP htmlspecialchars

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = SearchGoldenVectorsTest.class.getResourceAsStream("/site/url-golden/php-vectors.json")) {
            vectors = JsonMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String[] rawRequest(JsonNode in) {
        String path = "/index.php" + in.get("pathInfo").asString();
        List<String> query = new ArrayList<>();
        if (in.has("query")) {
            in.get("query").properties().forEach(e ->
                    query.add(e.getKey() + "=" + LegacyUrlBuilder.urlencode(e.getValue().asString())));
        }
        return new String[] {path, String.join("&", query)};
    }

    @Test
    void searchVectorsAgreeWithPhpExceptDocumentedDifferences() {
        int compared = 0;
        int differences = 0;
        for (JsonNode b : vectors.get("branches")) {
            LegacyUrlStyle style = LegacyUrlGoldenVectorsTest.style(b.get("config"));
            LegacyUrlParser parser = new LegacyUrlParser(new LegacyUrlBuilder(style));
            boolean handled = !style.routeAliases() && style.depr() == '/';
            for (JsonNode v : b.get("parse")) {
                String id = v.get("id").asString();
                if (!id.startsWith("search.q.")) {
                    continue;
                }
                String where = b.get("branch").asString() + ":" + id;
                String[] req = rawRequest(v.get("input"));
                Optional<LegacyUrlParser.Match> m = parser.parseSearch(req[0], req[1]);
                if (!handled) {
                    assertThat(m).as(where + " 本分支不承接").isEmpty();
                    continue;
                }
                assertThat(m).as(where + " " + req[0] + "?" + req[1]).isPresent();
                assertThat(v.has("error")).as(where).isFalse();
                assertThat(v.get("dispatch").asString()).as(where).isEqualTo("vod/search");
                assertThat(v.get("handlerExists").asBoolean()).as(where).isTrue();
                SiteRoute.VodSearch route = (SiteRoute.VodSearch) m.get().route();
                JsonNode php = v.get("param");
                assertThat(php.get("page").asInt()).as(where + " page").isEqualTo(route.page());
                Map<String, String> java = route.filters().params();
                if (DIFFERENCES.containsKey(id)) {
                    assertThat(java.get("wd")).as(where).isEqualTo(DIFFERENCES.get(id)[0]);
                    assertThat(php.get("wd").asString()).as(where).isEqualTo(DIFFERENCES.get(id)[1]);
                    differences++;
                    continue;
                }
                for (String key : List.of("wd", "area", "lang", "year", "level", "class", "by", "order")) {
                    String phpValue = php.has(key) ? php.get(key).asString() : "";
                    String javaValue = java.get(key);
                    if (javaValue == null) {
                        assertThat(phpValue).as(where + " " + key + " 未设置").isIn("", "0");
                    } else {
                        assertThat(phpValue).as(where + " " + key).isEqualTo(javaValue);
                    }
                }
                compared++;
            }
        }
        // 默认、rewrite、suffix_hide 三个分支各 11 条一致、3 条差异
        assertThat(compared).isEqualTo(33);
        assertThat(differences).isEqualTo(9);
    }
}

package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B05（15 文档第 3.6 节，队长裁定 D-B05-5、D-B05-7）：{@link LegacyUrlParser#parseVodList} 承接 vodsearch/vodshow 位置参数、
 * vod/show 路径参数形式与 ajax_search/ajax_show。位置映射以 PHP 黄金样本 content/vodlist/php-golden.json（PHP 8.0）与
 * php73-golden.json（PHP 7.3）的 urls[vodsearch.*]、urls[vodshow.*] 逐条锁定：位置形式的解析结果必须与同一组旧参数的查询串形式相同。
 */
class LegacyUrlParserVodListTest {

    private static final List<LegacyUrlStyle> STYLES = LegacyUrlParserSearchTest.STYLES;
    private static final LegacyUrlStyle STYLE = LegacyUrlStyle.REPO_DEFAULT;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static LegacyUrlParser parser(LegacyUrlStyle style) {
        return new LegacyUrlParser(new LegacyUrlBuilder(style));
    }

    private static LegacyUrlParser.ListMatch list(String path, String query) {
        return parser(STYLE).parseVodList(path, query).orElseThrow(() -> new AssertionError(path + "?" + query));
    }

    /** 路径中的非 ASCII 字符按 UTF-8 百分号编码（浏览器发出的原始请求路径）。 */
    private static String encodePath(String decoded) {
        StringBuilder sb = new StringBuilder();
        for (byte b : decoded.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c > 0x20 && c < 0x7f) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static String query(Map<String, String> params) {
        List<String> out = new ArrayList<>();
        new TreeMap<>(params).forEach((k, v) -> out.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return String.join("&", out);
    }

    private static List<JsonNode> goldenUrls(String file, String prefix) throws Exception {
        try (InputStream in = LegacyUrlParserVodListTest.class.getResourceAsStream("/content/vodlist/" + file)) {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode u : JSON.readTree(in).get("urls")) {
                if (u.get("id").asString().startsWith(prefix)) {
                    out.add(u);
                }
            }
            return out;
        }
    }

    private static Map<String, String> params(JsonNode u) {
        Map<String, String> m = new TreeMap<>();
        u.get("param").properties().forEach(e -> m.put(e.getKey(), e.getValue().asString()));
        return m;
    }

    // ---------------------------------------------------------------- 位置参数（PHP 黄金）

    @Test
    void vodsearchSlotsMatchTheLegacyParametersInBothPhpVersions() throws Exception {
        int n = 0;
        for (String file : List.of("php-golden.json", "php73-golden.json")) {
            for (JsonNode u : goldenUrls(file, "vodsearch.")) {
                String path = "/index.php" + encodePath(u.get("pathInfo").asString());
                LegacyUrlParser.ListMatch positional = list(path, null);
                LegacyUrlParser.ListMatch byQuery = list("/index.php/vod/search.html", query(params(u)));
                assertThat(positional.route()).as(u.get("id").asString()).isEqualTo(byQuery.route());
                assertThat(positional.canonical()).isFalse();
                assertThat(positional.ajax()).isFalse();
                n++;
            }
        }
        assertThat(n).isGreaterThanOrEqualTo(2 * 17);
        // 多出的段并入第 1 位：年份区间不能写在位置里（旧版同样得到 wd="-"、tag=2018、year=2020）
        SearchFilters f = ((SiteRoute.VodSearch) list("/index.php/vodsearch/-------------2018-2020.html", null).route())
                .filters();
        assertThat(f.keyword()).isEqualTo("-");
        assertThat(f.tag()).isEqualTo("2018");
        assertThat(f.year()).isEqualTo("2020");
        // 0 与空位都是未传（SSR 逐键归一）
        assertThat(((SiteRoute.VodSearch) list("/index.php/vodsearch/0-0------0-----0-.html", null).route()).filters()
                .isEmpty()).isTrue();
    }

    @Test
    void vodshowSlotsMatchTheLegacyParametersInBothPhpVersions() throws Exception {
        int n = 0;
        for (String file : List.of("php-golden.json", "php73-golden.json")) {
            for (JsonNode u : goldenUrls(file, "vodshow.")) {
                Map<String, String> p = params(u);
                String id = p.remove("id");
                LegacyUrlParser.ListMatch positional = list("/index.php" + encodePath(u.get("pathInfo").asString()), null);
                LegacyUrlParser.ListMatch byQuery = list("/index.php/vod/show/id/" + id + ".html", query(p));
                assertThat(positional.route()).as(u.get("id").asString()).isEqualTo(byQuery.route());
                assertThat(((SiteRoute.VodShow) positional.route()).categoryId()).isEqualTo(1);
                assertThat(positional.canonical()).isFalse();
                n++;
            }
        }
        assertThat(n).isGreaterThanOrEqualTo(2 * 12);
        // 多于 12 段、分类缺失或不是规范数字：不认
        for (String path : List.of("/index.php/vodshow/1-a-b-c-d-e-f-g-h-i-j-k-l.html", "/index.php/vodshow/-----------.html",
                "/index.php/vodshow/01-----------.html", "/index.php/vodshow/abc-----------.html",
                "/index.php/vodshow/65536-----------.html", "/index.php/vodshow/0-----------.html")) {
            assertThat(parser(STYLE).parseVodList(path, null)).as(path).isEmpty();
        }
    }

    // ---------------------------------------------------------------- vod/show 路径参数形式

    @Test
    void showPathParametersEqualTheQueryFormAndQueryOverridesThePath() {
        String area = "%E5%A4%A7%E9%99%86";
        LegacyUrlParser.ListMatch path = list("/index.php/vod/show/area/" + area + "/by/hits/id/6/page/2.html", null);
        LegacyUrlParser.ListMatch byQuery = list("/index.php/vod/show/id/6.html", "area=" + area + "&by=hits&page=2");
        assertThat(path.route()).isEqualTo(byQuery.route());
        SiteRoute.VodShow show = (SiteRoute.VodShow) path.route();
        assertThat(show.categoryId()).isEqualTo(6);
        assertThat(show.page()).isEqualTo(2);
        assertThat(show.filters().area()).isEqualTo("大陆");
        assertThat(path.canonical()).isFalse();
        assertThat(byQuery.canonical()).isTrue();
        // 查询串覆盖路径中的同名值（旧 array_merge($input, $_REQUEST)），id 也可由查询串给出
        SiteRoute.VodShow over = (SiteRoute.VodShow) list("/index.php/vod/show/area/x/id/6.html", "area=y&id=7").route();
        assertThat(over.filters().area()).isEqualTo("y");
        assertThat(over.categoryId()).isEqualTo(7);
        // 路径中的 + 是字面值，查询串中的 + 是空格
        assertThat(((SiteRoute.VodShow) list("/index.php/vod/show/id/6/tag/a+b.html", null).route()).filters().tag())
                .isEqualTo("a+b");
        assertThat(((SiteRoute.VodShow) list("/index.php/vod/show/id/6.html", "tag=a+b").route()).filters().tag())
                .isEqualTo("a b");
        // 无条件
        assertThat(((SiteRoute.VodShow) list("/index.php/vod/show/id/6.html", null).route()).filters().isEmpty()).isTrue();
    }

    @Test
    void showRejectsTidDuplicatesUnknownKeysAndBadEncoding() {
        LegacyUrlParser p = parser(STYLE);
        assertThat(field(() -> p.parseVodList("/index.php/vod/show/id/6.html", "tid=1"))).isEqualTo("query");
        assertThat(field(() -> p.parseVodList("/index.php/vod/show/id/6/tid/1.html", null))).isEqualTo("tid");
        assertThat(field(() -> p.parseVodList("/index.php/vod/show/id/6/area/a/area/b.html", null))).isEqualTo("area");
        assertThat(field(() -> p.parseVodList("/index.php/vod/show/id/6/area.html", null))).isEqualTo("path");
        assertThat(field(() -> p.parseVodList("/index.php/vod/show/id/6/area/%FF.html", null))).isEqualTo("area");
        assertThat(field(() -> p.parseVodList("/index.php/vod/show/id/6.html", "area=a&area=b"))).isEqualTo("area");
        assertThat(field(() -> p.parseVodList("/index.php/vod/show/id/6.html", "page=0"))).isEqualTo("page");
        // 分类缺失或格式不对：不认（404），不是 400
        for (String path : List.of("/index.php/vod/show.html", "/index.php/vod/show/area/x.html",
                "/index.php/vod/show/id/06.html", "/index.php/vod/show/id/x.html", "/index.php/vod/show/id/0.html")) {
            assertThat(p.parseVodList(path, null)).as(path).isEmpty();
        }
        // 兼容模式 s 在这些入口上交还 PHP
        assertThat(p.parseVodList("/index.php/vod/show/id/6.html", "s=vod/search")).isEmpty();
    }

    private static String field(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
            return e.fieldErrors().getFirst().field();
        }
        throw new AssertionError("应为 INVALID_PARAMETER");
    }

    // ---------------------------------------------------------------- ajax 与 URL 形态

    @Test
    void ajaxEntriesAreFlaggedAndUseTheSameRules() {
        LegacyUrlParser.ListMatch s = list("/index.php/vod/ajax_search.html", "wd=x&page=2");
        assertThat(s.ajax()).isTrue();
        assertThat(s.route()).isEqualTo(list("/index.php/vod/search.html", "wd=x&page=2").route());
        LegacyUrlParser.ListMatch show = list("/index.php/vod/ajax_show/id/6/area/x.html", null);
        assertThat(show.ajax()).isTrue();
        assertThat(show.canonical()).isFalse();
        assertThat(show.route()).isEqualTo(list("/index.php/vod/show/id/6.html", "area=x").route());
        assertThatThrownBy(() -> parser(STYLE).parseVodList("/index.php/vod/ajax_search.html", "isend=1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void everySiteStyleGeneratesShowLinksThatParseBack() {
        for (LegacyUrlStyle style : STYLES) {
            LegacyUrlBuilder b = new LegacyUrlBuilder(style);
            LegacyUrlParser p = new LegacyUrlParser(b);
            SearchFilters f = SearchFilters.NONE.with("area", "大陆").with("letter", "A");
            for (int page : List.of(1, 2, 1000)) {
                SiteLink link = b.vodShowLink(6, f, page);
                LegacyUrlParser.ListMatch m = p.parseVodList("/" + link.path(), link.query().isEmpty() ? null : link.query())
                        .orElseThrow();
                assertThat(m.route()).as(style + " " + link.path()).isEqualTo(new SiteRoute.VodShow(6, f, page));
                assertThat(m.canonical()).isTrue();
            }
            // 去掉 index.php 的形式只在 rewrite 时认得
            assertThat(p.parseVodList("/vodshow/6-----------.html", null).isPresent()).isEqualTo(style.rewrite());
            assertThat(p.parseVodList("/vod/show/id/6.html", null).isPresent()).isEqualTo(style.rewrite());
        }
        assertThat(new LegacyUrlBuilder(STYLE).vodShowLink(6, SearchFilters.NONE.with("area", "大陆"), 2).relative())
                .isEqualTo("index.php/vod/show/id/6.html?area=%E5%A4%A7%E9%99%86&page=2");
    }
}

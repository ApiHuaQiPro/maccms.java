package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * B05（队长裁定 D-B05-5）：{@link LegacyUrlParser#parseModuleList} 与 {@link LegacyUrlBuilder#moduleListLink}——模块筛选/搜索页的
 * 键、逐键归一、400/404 的分界与链接往返（四种 URL 形态）。旧版 ID 对照见 ModuleSearchGoldenMySqlIT（PHP 黄金样本）。
 */
class LegacyUrlParserModuleListTest {

    private static final LegacyUrlParser P = new LegacyUrlParser(new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT));

    private static SiteRoute.ModuleList route(String path, String query) {
        return P.parseModuleList(path, query).orElseThrow(() -> new AssertionError(path + "?" + query)).route();
    }

    private static String field(String path, String query) {
        try {
            P.parseModuleList(path, query);
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
            return e.fieldErrors().getFirst().field();
        }
        throw new AssertionError("应为 INVALID_PARAMETER：" + path + "?" + query);
    }

    @Test
    void searchPagesAcceptOnlyTheirModuleKeysAndNormalizeLikeTheVideoSearch() {
        SiteRoute.ModuleList r = route("/index.php/art/search.html", "wd=+%E6%98%9F+&tid=021&level=1abc&letter=0"
                + "&ajax=1&page=2");
        assertThat(r.page()).isEqualTo(ModuleListPage.ART_SEARCH);
        assertThat(r.categoryId()).isNull();
        assertThat(r.pageNo()).isEqualTo(2);
        // wd 去首尾空白；tid、level 为旧 intval；letter=0 为未传；ajax 接受并忽略（D-B05-6）
        assertThat(r.filters().params()).containsExactly(Map.entry("level", "1"), Map.entry("tid", "21"),
                Map.entry("wd", "星"));
        assertThat(route("/index.php/art/search/", "tid=-3").filters().params()).containsEntry("tid", "-3");
        assertThat(route("/index.php/art/search", "tid=abc&level=00").filters().isEmpty()).isTrue();
        // 模块之间的键不同：角色没有 tid，文章没有 sex、rid
        assertThat(field("/index.php/role/search.html", "tid=1")).isEqualTo("query");
        assertThat(field("/index.php/art/search.html", "sex=%E5%A5%B3")).isEqualTo("query");
        assertThat(route("/index.php/actor/search.html", "sex=%E5%A5%B3&starsign=x&blood=O").filters().params())
                .containsOnlyKeys("blood", "sex", "starsign");
        assertThat(route("/index.php/role/search.html", "rid=101").filters().params()).containsEntry("rid", "101");
        // content 的同一套规则：排序白名单（不含 rnd）、letter 单值、level 1..9、重复键
        assertThat(field("/index.php/art/search.html", "by=rnd")).isEqualTo("sort");
        assertThat(field("/index.php/art/search.html", "by=foo")).isEqualTo("sort");
        assertThat(field("/index.php/art/search.html", "letter=AB")).isEqualTo("letter");
        assertThat(field("/index.php/art/search.html", "level=10")).isEqualTo("level");
        assertThat(field("/index.php/art/search.html", "wd=a&wd=b")).isEqualTo("wd");
        assertThat(field("/index.php/art/search.html", "wd=%FF")).isEqualTo("wd");
        assertThat(field("/index.php/art/search.html", "page=0")).isEqualTo("page");
        assertThat(field("/index.php/website/search.html", "ids=1,x")).isEqualTo("ids");
    }

    @Test
    void searchPathParameterFormsAndUnknownPagesAreNotRecognized() {
        for (String path : List.of("/index.php/art/search/wd/x.html", "/index.php/topic/show/id/1.html",
                "/index.php/role/show/id/1.html", "/index.php/plot/show/id/1.html", "/index.php/art/searchx.html",
                "/art/search.html", "/index.php/ART/search.html")) {
            assertThat(P.parseModuleList(path, null)).as(path).isEmpty();
        }
        assertThat(P.parseModuleList("/index.php/art/search.html", "s=art/search")).isEmpty();
    }

    @Test
    void showPagesTakeTheCategoryFromThePathOrQueryAndQueryOverridesThePath() {
        SiteRoute.ModuleList r = route("/index.php/art/show/id/21/letter/X.html", "letter=C");
        assertThat(r.page()).isEqualTo(ModuleListPage.ART_SHOW);
        assertThat(r.categoryId()).isEqualTo(21);
        assertThat(r.filters().params()).containsEntry("letter", "C");
        assertThat(route("/index.php/website/show/id/41.html", "id=42").categoryId()).isEqualTo(42);
        // 演员筛选页可以不给分类（旧版取第一个演员分类），其他模块不行
        assertThat(route("/index.php/actor/show.html", null).categoryId()).isNull();
        assertThat(P.parseModuleList("/index.php/art/show.html", null)).isEmpty();
        for (String path : List.of("/index.php/art/show/id/021.html", "/index.php/art/show/id/0.html",
                "/index.php/art/show/id/x.html", "/index.php/art/show/id/65536.html")) {
            assertThat(P.parseModuleList(path, null)).as(path).isEmpty();
        }
        assertThat(field("/index.php/art/show/id/21/letter.html", null)).isEqualTo("path");
        assertThat(field("/index.php/art/show/id/21/foo/1.html", null)).isEqualTo("foo");
        assertThat(field("/index.php/art/show/id/21/letter/A/letter/B.html", null)).isEqualTo("letter");
        assertThat(route("/index.php/art/ajax_show/id/21.html", null).page()).isEqualTo(ModuleListPage.ART_AJAX_SHOW);
    }

    @Test
    void generatedLinksParseBackToTheSameConditionsInEverySiteStyle() {
        Random rnd = new Random(20261017);
        List<String> values = List.of("星", "a b", "100%", "x/y", "A", "0-9", "1", "-2", "科幻,太空", "a_b");
        int checked = 0;
        for (LegacyUrlStyle style : LegacyUrlParserSearchTest.STYLES) {
            LegacyUrlBuilder b = new LegacyUrlBuilder(style);
            LegacyUrlParser p = new LegacyUrlParser(b);
            for (ModuleListPage page : ModuleListPage.values()) {
                for (int i = 0; i < 20; i++) {
                    ModuleFilters f = ModuleFilters.none(page.module());
                    for (String key : page.sortedKeys()) {
                        if (rnd.nextInt(3) == 0) {
                            try {
                                f = f.with(key, values.get(rnd.nextInt(values.size())));
                            } catch (IllegalArgumentException e) {
                                // 该键不接受这个值（如 level=星）：跳过
                            }
                        }
                    }
                    Integer category = page.show() ? 21 : null;
                    int pageNo = 1 + rnd.nextInt(3);
                    SiteLink link = b.moduleListLink(page, category, f, pageNo);
                    LegacyUrlParser.ModuleMatch m = p.parseModuleList("/" + link.path(),
                            link.query().isEmpty() ? null : link.query()).orElseThrow();
                    assertThat(m.route()).as(link.relative()).isEqualTo(new SiteRoute.ModuleList(page.page(), category, f,
                            pageNo));
                    assertThat(m.canonical()).isTrue();
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(4 * ModuleListPage.values().length * 20);
        assertThat(new LegacyUrlBuilder(LegacyUrlStyle.REPO_DEFAULT).moduleListLink(ModuleListPage.ART_AJAX_SHOW, 21,
                ModuleFilters.none("art").with("letter", "X"), 2).relative())
                .as("ajax 入口的链接指向完整页面").isEqualTo("index.php/art/show/id/21.html?letter=X&page=2");
    }
}

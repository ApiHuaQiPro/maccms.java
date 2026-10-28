package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 规则的边界与畸形规则（B20，28 文档第 3.4 节）：PHP 黄金覆盖不到、或旧版会死循环、整页报错的输入。与旧版一致的部分由
 * {@link CjRulesGoldenTest} 锁定。
 */
class CjRulesTest {

    private static final String L = "http://cj.example.test";

    private static Map<String, String> node(String... kv) {
        Map<String, String> n = new LinkedHashMap<>();
        n.put("sourcetype", "3");
        n.put("sourcecharset", "UTF-8");
        n.put("content_page", "1");
        n.put("content_page_rule", "1");
        for (int i = 0; i < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n;
    }

    private static CjRules rules(Map<String, String> node, Map<String, String> pages) {
        return new CjRules(node, u -> pages.containsKey(u) ? CjRules.bytes(CjRules.utf8(pages.get(u))) : null, () -> 1L);
    }

    @Test
    void catastrophicBacktrackingTimesOutAsRegexError() {
        StringBuilder html = new StringBuilder("<h1>");
        html.append("a".repeat(40)).append("!</h1>");
        Map<String, String> n = node("title_rule", "<h1>[内容]</h1>", "title_html_rule", "(.*a){25}x[|]y");
        CjRules r = new CjRules(n, u -> CjRules.bytes(html.toString()), () -> 1L, Duration.ofMillis(200));
        long t0 = System.nanoTime();
        Map<Object, Object> data = r.content(L + "/1.html");
        assertThat(System.nanoTime() - t0).isLessThan(Duration.ofSeconds(5).toNanos());
        // 同 PHP 超过回溯上限：preg_replace 返回 null
        assertThat(data).containsEntry("title", null);
    }

    @Test
    void zeroPageStepIsRefusedInsteadOfLoopingForever() {
        Map<String, String> n = node("sourcetype", "1", "urlpage", L + "/(*).html", "pagesize_start", "1", "pagesize_end", "5", "par_num", "0");
        assertThatThrownBy(() -> rules(n, Map.of()).urlList()).isInstanceOf(CjRules.RuleException.class)
                .hasMessageContaining("页码递增");
    }

    @Test
    void sequenceIsCapped() {
        Map<String, String> ok = node("sourcetype", "1", "urlpage", L + "/(*).html", "pagesize_start", "1",
                "pagesize_end", Integer.toString(CjRules.MAX_URLS), "par_num", "1");
        assertThat(rules(ok, Map.of()).urlList()).hasSize(CjRules.MAX_URLS);
        Map<String, String> over = new LinkedHashMap<>(ok);
        over.put("pagesize_end", Integer.toString(CjRules.MAX_URLS + 1));
        assertThatThrownBy(() -> rules(over, Map.of()).urlList()).isInstanceOf(CjRules.RuleException.class);
    }

    @Test
    void rssSourceTypeFailsLikeLegacy() {
        Map<String, String> n = node("sourcetype", "4", "urlpage", L + "/rss.xml");
        assertThat(rules(n, Map.of()).urlList()).containsExactly(L + "/rss.xml");
        assertThatThrownBy(() -> rules(n, Map.of(L + "/rss.xml", "<rss/>")).urlLists(L + "/rss.xml"))
                .isInstanceOf(CjRules.RuleException.class).hasMessageContaining("RSS");
    }

    @Test
    void listAllPagingIsCappedPerArticle() {
        StringBuilder pager = new StringBuilder("<div class=\"p\">");
        Map<String, String> pages = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            pager.append("<a href=\"/p/").append(i).append(".html\">").append(i).append("</a>");
            pages.put(L + "/p/" + i + ".html", "<div id=\"c\">第" + i + "页</div>");
        }
        pager.append("</div>");
        pages.put(L + "/a.html", "<div id=\"c\">首页</div>" + pager);
        Map<String, String> n = node("content_rule", "<div id=\"c\">[内容]</div>", "content_page_start", "<div class=\"p\">",
                "content_page_end", "</div>", "content_page_rule", "1");
        CjRules r = rules(n, pages);
        assertThatThrownBy(() -> r.content(L + "/a.html")).isInstanceOf(CjRules.RuleException.class).hasMessageContaining("页面超过");
        // 计数按每次 content 重新开始
        pages.put(L + "/b.html", "<div id=\"c\">只有一页</div>");
        assertThat(r.content(L + "/b.html")).containsEntry("content", CjRules.utf8("只有一页"));
    }

    @Test
    void nextPageLoopsEndThroughVisitedUrls() {
        Map<String, String> pages = Map.of(
                L + "/a/1.html", "<div id=\"c\">一</div><div class=\"p\"><a href=\"2.html\">下一页</a></div>",
                L + "/a/2.html", "<div id=\"c\">二</div><div class=\"p\"><a href=\"1.html\">下一页</a></div>");
        Map<String, String> n = node("content_rule", "<div id=\"c\">[内容]</div>", "content_page_start", "<div class=\"p\">",
                "content_page_end", "</div>", "content_page_rule", "2", "content_nextpage", "下一页");
        List<String> fetched = new ArrayList<>();
        CjRules r = new CjRules(n, u -> {
            fetched.add(u);
            return pages.containsKey(u) ? CjRules.bytes(CjRules.utf8(pages.get(u))) : null;
        }, () -> 1L);
        assertThat(r.content(L + "/a/1.html")).containsEntry("content", CjRules.utf8("一[page]二"));
        assertThat(fetched).containsExactly(L + "/a/1.html", L + "/a/2.html");
    }

    @Test
    void unsupportedPcreConstructsAreTreatedAsInvalidPatterns() {
        for (String body : List.of("a\\Kb", "(?R)", "(?1)", "(?(1)a|b)", "(*FAIL)", "(?|a)", "a\\u0041", "(?P>n)", "(?<a_b>x)")) {
            assertThat(CjPcre.compile("/" + body + "/i")).as(body).isNull();
        }
        // 修饰符：PHP 认得但这里不支持的，与未知的一样按无效
        for (String mod : List.of("U", "u", "A", "D", "J", "e", "q")) {
            assertThat(CjPcre.compile("/a/" + mod)).as(mod).isNull();
        }
        assertThat(CjPcre.compile("/a/ims x\n")).isNotNull();
        assertThat(CjPcre.compile("/abc")).isNull();
        assertThat(CjPcre.compile("/a\0b/")).isNull();
        assertThat(CjPcre.compile("/\\1(a)/")).isNotNull();
        assertThat(CjPcre.compile("/(a)\\2/")).isNull();
    }

    @Test
    void translationKeepsPcreMeaning() {
        assertThat(m("/[[:digit:]]+/", "a12b")).isEqualTo("12");
        assertThat(m("/a{,2}/", "a{,2}")).isEqualTo("a{,2}");
        assertThat(m("/[a&&b]/", "&")).isEqualTo("&");
        assertThat(m("/[]a]+/", "]a]")).isEqualTo("]a]");
        assertThat(m("/\\x41/i", "a")).isEqualTo("a");
        assertThat(m("/\\101/", "A")).isEqualTo("A");
        assertThat(m("/(a)\\1/", "aa")).isEqualTo("aa");
        assertThat(m("/(?#注释)b/", "ab")).isEqualTo("b");
        assertThat(m("/\\Q.*\\E/", "a.*")).isEqualTo(".*");
        assertThat(m("/[ #]+/x", "a #b")).isEqualTo(" #");
    }

    private static String m(String php, String subject) {
        Pattern p = CjPcre.compile(php);
        assertThat(p).as(php).isNotNull();
        java.util.regex.Matcher x = p.matcher(subject);
        assertThat(x.find()).as(php).isTrue();
        return x.group();
    }

    @Test
    void replacementFollowsPhpBackrefRules() {
        Pattern p = CjPcre.compile("/(b)(c)?/");
        CjPcre.Deadline d = CjPcre.Deadline.in(Duration.ofSeconds(1));
        assertThat(CjPcre.replaceAll(List.of(p), List.of("[$1|\\1|${1}|$2|$12|\\\\1|\\$1|$]"), "abd", d)).isEqualTo("a[b|b|b|||\\1|$1|$]d");
        assertThat(CjPcre.replaceAll(List.of(CjPcre.compile("//")), List.of("-"), "ab", d)).isEqualTo("-a-b-");
        assertThat(CjPcre.replaceAll(java.util.Arrays.asList(p, null), java.util.Arrays.asList("x", "y"), "abc", d)).isNull();
    }

    @Test
    void urlCheckFollowsParseUrlAndDirname() {
        CjRules r = rules(node(), Map.of());
        assertThat(r.urlCheck("x.html", "http://h/a/b.html")).isEqualTo("http://h/a/x.html");
        assertThat(r.urlCheck("x.html", "http://h/b.html")).isEqualTo("http://h//x.html");
        assertThat(r.urlCheck("x.html", "http://h")).isEqualTo("http://h/x.html");
        assertThat(r.urlCheck("/x.html", "https://u:p@H:81/a/b?q#f")).isEqualTo("https://H/x.html");
        assertThat(r.urlCheck("x.html", "http:///bad")).isEqualTo(":///x.html");
        assertThat(r.urlCheck("ftp://x/y", "http://h/")).isEqualTo("ftp://x/y");
        CjRules base = rules(node("page_base", "http://b/p/"), Map.of());
        assertThat(base.urlCheck("x.html", "http://h/a/b.html")).isEqualTo("http://b/p/x.html");
        assertThat(base.urlCheck("/x.html", "http://h/a/b.html")).isEqualTo("http://h/x.html");
    }

    @Test
    void jsonOfInvalidUtf8IsEmptyLikeFailedJsonEncode() {
        Map<Object, Object> data = new LinkedHashMap<>();
        data.put("title", "ÿ");
        assertThat(CjRules.json(data)).isEmpty();
        assertThat(CjRules.json(null)).isEqualTo("null");
        data.put("title", CjRules.utf8("中/"));
        data.put("time", 5L);
        data.put("x", Boolean.FALSE);
        data.put(7L, null);
        assertThat(CjRules.json(data)).isEqualTo("{\"title\":\"\\u4e2d\\/\",\"time\":5,\"x\":false,\"7\":null}");
    }
}

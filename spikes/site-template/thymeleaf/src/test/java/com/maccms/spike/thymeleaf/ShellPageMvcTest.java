package com.maccms.spike.thymeleaf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** MockMvc：正常文本、恶意输入转义、片段复用、空状态。两条渲染路径（视图解析 / 缓冲）都测。 */
@SpringBootTest
@AutoConfigureMockMvc
class ShellPageMvcTest {

    @Autowired
    MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/spike/view/normal", "/spike/buffered/normal"})
    void plainTextIsShownLiterallyAndChineseIsNotEntityEncoded(String path) throws Exception {
        String html = render(path, 200);

        assertThat(html).contains("<h1>最新更新</h1>")
                .contains("流浪地球 2：第一季 &amp; 特别篇 &lt;预告&gt;")
                .contains("《三体》“红岸”篇")
                .contains("Tom &amp; Jerry&#39;s &quot;Cat&quot;")
                .contains("<title>最新更新 - 示例影视站（合成）</title>")
                .doesNotContain("&#x")
                .doesNotContain("<预告>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/spike/view/normal", "/spike/buffered/normal"})
    void layoutAndCardFragmentsAreReused(String path) throws Exception {
        String html = render(path, 200);

        assertThat(count(html, "class=\"video-card\"")).isEqualTo(3);
        assertThat(count(html, "<header class=\"site-header\"")).isEqualTo(1);
        assertThat(count(html, "<footer class=\"site-footer\"")).isEqualTo(1);
        assertThat(count(html, "<link rel=\"canonical\"")).isEqualTo(1);
        assertThat(count(html, "<h1>")).isEqualTo(1);
        assertThat(html).startsWith("<!DOCTYPE html>").contains("<html lang=\"zh-CN\">")
                .doesNotContain("th:").doesNotContain("xmlns:th");
    }

    @Test
    void bothRenderPathsProduceTheSameMarkup() throws Exception {
        assertThat(render("/spike/view/normal", 200)).isEqualTo(render("/spike/buffered/normal", 200));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/spike/view/xss", "/spike/buffered/xss"})
    void maliciousStringsAreEscapedInTextAndAttributes(String path) throws Exception {
        String html = render(path, 200);

        // 文本上下文：标签被转义
        assertThat(html).doesNotContain("<script>alert").doesNotContain("<svg").doesNotContain("<img src=x")
                .contains("&lt;script&gt;alert(&#39;h1&#39;)&lt;/script&gt;")
                .contains("&lt;/title&gt;&lt;script&gt;alert(4)&lt;/script&gt;");
        // 属性上下文：引号被转义，无法跳出属性值注入事件属性
        assertThat(html).doesNotContain("\" onerror=\"").doesNotContain("\" onload=\"")
                .contains("src=\"x&quot; onerror=&quot;alert(2)\"")
                .contains("content=\"&quot; onload=&quot;alert(5)\"");
        // 用真实 HTML 解析器复核：没有注入出新元素或事件属性，恶意串只作为文本/属性值存在
        Document doc = Jsoup.parse(html);
        assertThat(doc.select("script, svg, img[src=x]")).isEmpty();
        assertThat(doc.getAllElements().stream().flatMap(e -> e.attributes().asList().stream())
                .filter(a -> a.getKey().toLowerCase().startsWith("on")).toList()).isEmpty();
        assertThat(doc.selectFirst("h1").text()).isEqualTo("<script>alert('h1')</script>");
        assertThat(doc.selectFirst("img").attr("src")).isEqualTo("x\" onerror=\"alert(2)");
        assertThat(doc.title()).isEqualTo("</title><script>alert(4)</script> - '><svg onload=alert(6)>");
        // 转义不等于 URL 校验：javascript: 协议原样进入 href，必须由 URL 生成器/白名单拦截
        assertThat(doc.selectFirst(".video-card a").attr("href")).isEqualTo("javascript:alert(7)");
    }

    @Test
    void plainTextRoundTripsThroughHtmlParser() throws Exception {
        Document doc = Jsoup.parse(render("/spike/buffered/normal", 200));
        assertThat(doc.select(".video-card__name").eachText())
                .containsExactly("流浪地球 2：第一季 & 特别篇 <预告>", "《三体》“红岸”篇", "Tom & Jerry's \"Cat\"");
        assertThat(doc.selectFirst("meta[name=description]").attr("content")).isEqualTo("《三体》与“红岸”基地 & 更多");
    }

    @Test
    void emptyListShowsEmptyState() throws Exception {
        String html = render("/spike/buffered/empty", 200);
        assertThat(html).contains("暂无内容").doesNotContain("video-card");
    }

    @Test
    void unknownFixtureIs404() throws Exception {
        assertThat(mvc.perform(get("/spike/buffered/nope")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    private String render(String path, int status) throws Exception {
        MvcResult r = mvc.perform(get(path)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(r.getResponse().getContentType()).isEqualTo("text/html;charset=UTF-8");
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static int count(String s, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}

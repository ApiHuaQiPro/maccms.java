package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.web.jte.StaticTemplates;
import gg.jte.html.OwaspHtmlTemplateOutput;
import gg.jte.models.runtime.JteModel;
import gg.jte.output.StringOutput;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * site 共享组件（videoCard、pagination）的结构与转义测试：
 * 语义化结构、空图占位、分页禁用状态；恶意 title/remarks 只按文本输出，没有脚本/事件/内联样式。
 * URL 只能传 SafeUrl——构造 {@code new SafeUrl("javascript:...")} 直接抛 IllegalArgumentException（SafeUrlTest 覆盖）。
 */
class SiteComponentsRenderTest {

    private static String renderVideoCard(String title, String remarks, SafeUrl detailUrl,
                                          Optional<SafeUrl> imageUrl) {
        return render(StaticTemplates.INSTANCE.siteComponentsVideoCard(title, remarks, detailUrl, imageUrl));
    }

    private static String renderPagination(int currentPage, int pageCount, Optional<SafeUrl> prev,
                                           Optional<SafeUrl> next) {
        return render(StaticTemplates.INSTANCE.siteComponentsPagination(currentPage, pageCount, prev, next));
    }

    /** 用 OwaspHtmlTemplateOutput 渲染，与生产预编译模板运行时的 HTML 策略输出一致。 */
    private static String render(JteModel model) {
        StringOutput out = new StringOutput();
        model.render(new OwaspHtmlTemplateOutput(out));
        return out.toString();
    }

    private static final SafeUrl DETAIL = new SafeUrl("/vod/1.html");
    private static final SafeUrl IMAGE = new SafeUrl("https://img.example.test/a.jpg");
    private static final String EVIL = "<img src=x onerror=\"alert(1)\"> & \"引号\" <script>alert(2)</script>";

    @Test
    void videoCardHasSemanticStructureAndEscapesText() {
        String html = renderVideoCard(EVIL, EVIL, DETAIL, Optional.of(IMAGE));
        assertThat(html).doesNotContain("<script>");
        Document doc = Jsoup.parseBodyFragment(html);
        // 恶意文本只作为文本/属性值存在：没有解析出脚本、事件属性或注入的 img 元素
        assertThat(doc.select("script")).isEmpty();
        for (Element e : doc.getAllElements()) {
            assertThat(e.attributes().asList()).noneMatch(a -> a.getKey().startsWith("on"));
        }
        assertThat(doc.select("img")).hasSize(1);
        Element card = doc.selectFirst("article.video-card");
        assertThat(card).isNotNull();
        Element link = card.selectFirst("a.video-card__link");
        assertThat(link.attr("href")).isEqualTo("/vod/1.html");
        Element img = link.selectFirst("img.video-card__image");
        assertThat(img.attr("src")).isEqualTo("https://img.example.test/a.jpg");
        assertThat(img.attr("alt")).isEqualTo(EVIL);
        assertThat(img.attr("loading")).isEqualTo("lazy");
        assertThat(link.selectFirst("h3.video-card__title").text()).isEqualTo(EVIL);
        assertThat(link.selectFirst("p.video-card__remarks").text()).isEqualTo(EVIL);
        assertThat(doc.select(".video-card__placeholder")).isEmpty();
    }

    @Test
    void videoCardWithoutImageRendersPlaceholderWithAriaHidden() {
        Document doc = Jsoup.parseBodyFragment(renderVideoCard("标题", "", DETAIL, Optional.empty()));
        assertThat(doc.select("img")).isEmpty();
        Element placeholder = doc.selectFirst(".video-card__placeholder");
        assertThat(placeholder).isNotNull();
        assertThat(placeholder.attr("aria-hidden")).isEqualTo("true");
        // remarks 为空时不输出备注元素
        assertThat(doc.select(".video-card__remarks")).isEmpty();
        assertThat(doc.selectFirst("h3.video-card__title").text()).isEqualTo("标题");
    }

    @Test
    void paginationWithBothLinksIsNavWithPrevNext() {
        String html = renderPagination(3, 10, Optional.of(new SafeUrl("/vod/type/2-2.html")),
                Optional.of(new SafeUrl("/vod/type/2-4.html")));
        Document doc = Jsoup.parseBodyFragment(html);
        Element nav = doc.selectFirst("nav.pagination");
        assertThat(nav.attr("aria-label")).isEqualTo("分页");
        Element prev = nav.selectFirst("a.pagination__link--prev");
        assertThat(prev.attr("href")).isEqualTo("/vod/type/2-2.html");
        assertThat(prev.attr("rel")).isEqualTo("prev");
        Element next = nav.selectFirst("a.pagination__link--next");
        assertThat(next.attr("href")).isEqualTo("/vod/type/2-4.html");
        assertThat(next.attr("rel")).isEqualTo("next");
        assertThat(nav.selectFirst(".pagination__status").text()).isEqualTo("第 3 / 10 页");
        assertThat(nav.selectFirst(".pagination__status").attr("aria-current")).isEqualTo("page");
    }

    @Test
    void paginationEdgesRenderDisabledSpansInsteadOfLinks() {
        Document first = Jsoup.parseBodyFragment(
                renderPagination(1, 1, Optional.empty(), Optional.empty()));
        assertThat(first.select("a")).isEmpty();
        assertThat(first.select(".pagination__link--disabled")).hasSize(2);
        for (Element disabled : first.select(".pagination__link--disabled")) {
            assertThat(disabled.tagName()).isEqualTo("span");
            assertThat(disabled.attr("aria-disabled")).isEqualTo("true");
        }
        Document middle = Jsoup.parseBodyFragment(renderPagination(2, 5,
                Optional.of(new SafeUrl("/vod/type/2-1.html")), Optional.empty()));
        assertThat(middle.select("a.pagination__link--prev")).hasSize(1);
        assertThat(middle.select("a.pagination__link--next")).isEmpty();
        assertThat(middle.select(".pagination__link--next")).hasSize(1);
    }
}

package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.categories;
import static com.maccms.backend.site.web.SiteContentFixtures.detail;
import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * W2 关闭时三页的"页面无外部域名资源"检查（B02，路线图第 4.2 节；11 文档第 5 节）。子类给出图片配置与测试数据：
 * 用含外部图片地址（{@code mac:}、协议相对、{@code img_api} 命中、{@code remote} 前缀等分支）的合成数据渲染首页、分类、详情，
 * 断言任何元素属性里都没有外部主机（导航链接 {@code a[href]} 除外，它不是资源加载），外部图片位置显示占位，本站图片照常输出。
 */
abstract class ExternalImagesPageCheck {

    static final String SITE_HOST = "www.maccms.test";
    /** 只出现在导航链接里的外部主机（允许）。 */
    static final String LINK_HOST = "link.ext.test";

    private static final Pattern ABSOLUTE = Pattern.compile("(?i)(?:\\b[a-z][a-z0-9+.-]*:)?//([^/\\s\"'?#\\\\]+)");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ContentReadService content;

    /** 本配置下必须被拦下的外部图片原始值（库内字段），以及它们的主机名（页面里一处都不能出现）。 */
    abstract List<String> externalPics();

    abstract List<String> externalHosts();

    /** 本配置下照常输出的图片：原始值与期望的 src，交替排列。 */
    abstract List<String> localPicsAndSrc();

    private List<VideoSummaryDto> summaries() {
        List<VideoSummaryDto> out = new ArrayList<>();
        long id = 100;
        for (String pic : externalPics()) {
            out.add(summary(id++, "外部图片 " + id, pic, ref(1, "电影", 0)));
        }
        List<String> local = localPicsAndSrc();
        for (int i = 0; i < local.size(); i += 2) {
            out.add(summary(id++, "本站图片 " + id, local.get(i), ref(1, "电影", 0)));
        }
        return out;
    }

    @BeforeEach
    void data() {
        when(content.listCategories()).thenReturn(categories());
        when(content.listVideos(any())).thenAnswer(inv -> {
            VideoListQuery q = inv.getArgument(0);
            List<VideoSummaryDto> items = summaries();
            return page(items, q.page(), q.pageSize(), items.size());
        });
        StringBuilder body = new StringBuilder("<p>正文</p>");
        for (String pic : externalPics()) {
            body.append("<img src=\"").append(pic).append("\" alt=\"外部\">");
        }
        List<String> local = localPicsAndSrc();
        for (int i = 0; i < local.size(); i += 2) {
            body.append("<img src=\"").append(local.get(i)).append("\" alt=\"本站\">");
        }
        body.append("<a href=\"https://").append(LINK_HOST).append("/x.html\">外部链接</a>");
        when(content.getVideo(9)).thenReturn(detail(9, "合成详情", externalPics().get(0), "简介", body.toString()));
    }

    private Document render(String path) throws Exception {
        MvcResult r = mvc.perform(get(path)).andReturn();
        String html = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(html).isEqualTo(200);
        for (String host : externalHosts()) {
            assertThat(html.toLowerCase(Locale.ROOT)).as(path + " 中出现外部主机 " + host).doesNotContain(host);
        }
        Document doc = Jsoup.parse(html);
        List<String> found = new ArrayList<>();
        for (Element el : doc.getAllElements()) {
            for (Attribute a : el.attributes()) {
                if (el.normalName().equals("a") && a.getKey().equalsIgnoreCase("href")) {
                    continue;
                }
                Matcher m = ABSOLUTE.matcher(a.getValue());
                while (m.find()) {
                    String host = m.group(1).replaceAll(":\\d+$", "").toLowerCase(Locale.ROOT);
                    if (!host.equals(SITE_HOST)) {
                        found.add("<" + el.normalName() + " " + a.getKey() + "> → " + host);
                    }
                }
            }
        }
        assertThat(found).as(path + " 的资源属性中出现外部主机").isEmpty();
        return doc;
    }

    private void assertCards(Document doc) {
        List<Element> cards = doc.select("article.video-card");
        assertThat(cards).isNotEmpty();
        List<String> expectedSrc = new ArrayList<>();
        List<String> local = localPicsAndSrc();
        for (int i = 1; i < local.size(); i += 2) {
            expectedSrc.add(local.get(i));
        }
        assertThat(doc.select("article.video-card img").eachAttr("src")).containsOnlyElementsOf(expectedSrc)
                .containsAll(expectedSrc);
        assertThat(doc.select("article.video-card .video-card__placeholder").size())
                .as("每张外部海报都显示占位").isGreaterThanOrEqualTo(externalPics().size());
    }

    @Test
    void homeHasNoExternalImageHosts() throws Exception {
        assertCards(render("/"));
    }

    @Test
    void categoryHasNoExternalImageHosts() throws Exception {
        assertCards(render("/index.php/vod/type/id/1.html"));
    }

    @Test
    void detailHasNoExternalImageHostsAndKeepsLocalImages() throws Exception {
        Document doc = render("/index.php/vod/detail/id/9.html");
        assertThat(doc.select(".video-detail__image")).as("外部海报不输出").isEmpty();
        assertThat(doc.select(".video-detail__placeholder")).hasSize(1);
        Element body = doc.selectFirst(".detail-content");
        List<String> expectedSrc = new ArrayList<>();
        List<String> local = localPicsAndSrc();
        for (int i = 1; i < local.size(); i += 2) {
            expectedSrc.add(local.get(i));
        }
        assertThat(body.select("img").eachAttr("src")).as("正文只留本站图片，外部图片整张删除")
                .containsExactlyElementsOf(expectedSrc);
        // 导航链接不是资源加载，照常输出（rel 与 MACC-12 一致）
        assertThat(body.select("a").eachAttr("href")).containsExactly("https://" + LINK_HOST + "/x.html");
    }
}

package com.maccms.backend.site.web;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.ActorDetailDto;
import com.maccms.backend.content.application.view.ActorSummaryDto;
import com.maccms.backend.content.application.view.ArticleContentPageDto;
import com.maccms.backend.content.application.view.ArticleDetailDto;
import com.maccms.backend.content.application.view.ArticleSummaryDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.CategoryRefDto;
import com.maccms.backend.content.application.view.PlotDto;
import com.maccms.backend.content.application.view.RoleDetailDto;
import com.maccms.backend.content.application.view.RoleSummaryDto;
import com.maccms.backend.content.application.view.RoleVideoDto;
import com.maccms.backend.content.application.view.ScreenshotDto;
import com.maccms.backend.content.application.view.TopicDetailDto;
import com.maccms.backend.content.application.view.TopicSummaryDto;
import com.maccms.backend.content.application.view.VideoPlotListResponse;
import com.maccms.backend.content.application.view.WebsiteDetailDto;
import com.maccms.backend.content.application.view.WebsiteSummaryDto;
import java.math.BigDecimal;
import java.util.List;

/** 文章、专题、演员、角色、网址、剧情页测试用的合成数据（地址都在 .test / example.invalid 下）。 */
final class LibraryPageFixtures {

    static final long UPDATED = SiteContentFixtures.UPDATED_AT;
    static final BigDecimal ZERO = new BigDecimal("0.0");
    /** 每类正文都含的恶意片段与合法富文本（07 文档的过滤规则）。 */
    static final String RICH = "<p onclick=\"steal()\">正文 &amp; <b>重点</b></p><script>alert('X')</script>"
            + "<ul><li>列表</li></ul><table><tr><td>单元格</td></tr></table>"
            + "<a href=\"javascript:alert(1)\">坏链接</a><a href=\"https://ok.example.invalid/x\">好链接</a>"
            + "<img src=\"data:image/png;base64,AA\" alt=\"坏图\"><img src=\"/upload/art/ok.jpg\" alt=\"好图\">"
            + "<p style=\"color:red\">样式</p>";

    static CategoryListResponse artTypes() {
        return new CategoryListResponse(List.of(
                SiteContentFixtures.category(21, "资讯", 0, "", "", "", List.of(
                        SiteContentFixtures.category(22, "国内", 21, "", "", "", List.of()))),
                SiteContentFixtures.category(23, "娱乐", 0, "", "", "", List.of())));
    }

    static CategoryRefDto artRef() {
        return new CategoryRefDto(22, "国内", "en22", 21);
    }

    static ArticleSummaryDto artSummary(long id) {
        return new ArticleSummaryDto(id, "文章 " + id + " &amp; <i>", "", "a" + id, "W", "", artRef(), "来源", "作者",
                "标签", "", "upload/art/" + id + ".jpg", "", "", "简介", "备注" + id, 0, ZERO, 0, 0, 0, 0, 0, 0, 0,
                UPDATED, UPDATED, false);
    }

    static ArticleDetailDto article(long id, int pages) {
        List<ArticleContentPageDto> parts = new java.util.ArrayList<>();
        for (int i = 1; i <= pages; i++) {
            parts.add(new ArticleContentPageDto(i, "第" + i + "页标题", "备注" + i, "<p>PAGE-" + i + "-BODY</p>"
                    + (i == 1 ? RICH : "")));
        }
        return new ArticleDetailDto(id, "长文章 &amp; 测试", "副标题", "a" + id, "C", "", artRef(), "新华社", "记者甲",
                "新闻,测试", "", "upload/art/" + id + ".jpg", "", "", "文章简介 <b>粗</b>", "", 0, ZERO, 0, 0, 0, 0, 0,
                0, 0, 0, UPDATED, UPDATED, List.copyOf(parts),
                List.of(new ScreenshotDto(1, "截图一", "upload/shot/1.jpg"), new ScreenshotDto(2, "坏截图", "javascript:x")));
    }

    static TopicSummaryDto topicSummary(int id) {
        return new TopicSummaryDto(id, "专题 " + id, "", "t" + id, "Z", "", 0, "", "", "upload/topic/" + id + ".jpg", "",
                "", "", "", "", "", "专题备注", 0, ZERO, 0, 0, 0, 0, 0, 0, 0, UPDATED, UPDATED);
    }

    static TopicDetailDto topic(int id) {
        return new TopicDetailDto(id, "专题 " + id, "", "t" + id, "Z", "", 0, "", "标签", "upload/topic/" + id + ".jpg",
                "", "", "专题标题", "关键词", "专题描述", "专题简介", "", 0, ZERO, 0, 0, 0, 0, 0, 0, 0, 0, UPDATED, UPDATED,
                RICH, List.of(SiteContentFixtures.summary(9, "关联视频", "upload/vod/9.jpg",
                        SiteContentFixtures.ref(6, "动作片", 1))), List.of(artSummary(55)));
    }

    static ActorSummaryDto actorSummary(long id) {
        return new ActorSummaryDto(id, "演员 " + id, "y" + id, "", "Y", "", new CategoryRefDto(31, "男演员", "en31", 0),
                "男", "upload/actor/" + id + ".jpg", "", "", "大陆", "", "", "", "", "", "", "", "", "", "", 0, ZERO, 0,
                0, 0, 0, 0, 0, 0, UPDATED, UPDATED);
    }

    static ActorDetailDto actor(long id) {
        return new ActorDetailDto(id, "演员 " + id, "yanyuan", "艺名", "Y", "", new CategoryRefDto(31, "男演员", "en31", 0),
                "男", "upload/actor/" + id + ".jpg", "演员简介", "", "大陆", "180cm", "70kg", "1990-01-01", "北京", "A",
                "白羊座", "某学院", "作品甲", "标签", "", 0, ZERO, 0, 0, 0, 0, 0, 0, 0, 0, UPDATED, UPDATED, RICH);
    }

    static CategoryListResponse actorTypes() {
        return new CategoryListResponse(List.of(SiteContentFixtures.category(31, "男演员", 0, "", "", "", List.of())));
    }

    static RoleSummaryDto roleSummary(long id) {
        return new RoleSummaryDto(id, new RoleVideoDto(9, "所属视频", "v9", 6), "角色 " + id, "j" + id, "J", "", "演员甲",
                "", "upload/role/" + id + ".jpg", 0, 0, ZERO, 0, 0, 0, 0, 0, 0, 0, UPDATED, UPDATED);
    }

    static RoleDetailDto role(long id) {
        return new RoleDetailDto(id, new RoleVideoDto(9, "所属视频", "v9", 6), "角色 " + id, "j" + id, "J", "", "演员甲",
                "角色备注", "upload/role/" + id + ".jpg", 0, 0, ZERO, 0, 0, 0, 0, 0, 0, 0, 0, UPDATED, UPDATED, RICH);
    }

    static WebsiteSummaryDto websiteSummary(long id) {
        return new WebsiteSummaryDto(id, "网址 " + id, "", "w" + id, "W", "", new CategoryRefDto(41, "门户", "en41", 0),
                0, "https://site.example.invalid/", "", "upload/logo/" + id + ".png", "", "", "", "", "", "", 0, ZERO, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, UPDATED, UPDATED);
    }

    static WebsiteDetailDto website(long id, String url) {
        return new WebsiteDetailDto(id, "网址 " + id, "", "w" + id, "W", "", new CategoryRefDto(41, "门户", "en41", 0), 0,
                url, "", "upload/logo/" + id + ".png", "大陆", "中文", "标签", "", "网址简介", "", 0, ZERO, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, UPDATED, UPDATED, RICH, List.of());
    }

    static CategoryListResponse websiteTypes() {
        return new CategoryListResponse(List.of(SiteContentFixtures.category(41, "门户", 0, "", "", "", List.of())));
    }

    static VideoPlotListResponse plots(long vodId, int n) {
        List<PlotDto> items = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            items.add(new PlotDto(i, "第" + i + "集剧情", "<p>PLOT-" + i + "</p>" + (i == 1 ? RICH : "")));
        }
        return new VideoPlotListResponse(vodId, List.copyOf(items));
    }

    static <T> PageResponse<T> page(List<T> items, int page, long total) {
        return PageResponse.of(items, page, 20, total);
    }

    private LibraryPageFixtures() {
    }
}

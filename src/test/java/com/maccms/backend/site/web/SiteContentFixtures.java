package com.maccms.backend.site.web;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.CategoryExtendDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.CategoryRefDto;
import com.maccms.backend.content.application.view.VideoDetailDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;

/**
 * 三页测试用的合成数据（全部为虚构值，域名在保留顶级域 .test 下）。
 * 分类树：1 电影（子分类 6 动作片、7 喜剧片）、2 连续剧、3 综艺（无视频）、4 动漫、5 纪录片。
 */
public final class SiteContentFixtures {

    /** 合成的"不应出现在页面/日志中"的标记。 */
    public static final String LEAK = "SYNTHETIC-LEAK-7f3a";
    /** 2026-10-01 12:00:00 +08:00 的 Unix 秒。 */
    public static final long UPDATED_AT = 1_790_827_200L;

    private static final CategoryExtendDto NO_EXTEND = new CategoryExtendDto("", "", "", "", "", "", "", "");

    private SiteContentFixtures() {
    }

    public static CategoryDto category(int id, String name, int parentId, String title, String keywords,
                                       String description, List<CategoryDto> children) {
        return new CategoryDto(id, name, "en" + id, id, parentId, title, keywords, description, "", "", NO_EXTEND,
                children);
    }

    public static CategoryListResponse categories() {
        CategoryDto action = category(6, "动作片", 1, "", "", "", List.of());
        CategoryDto comedy = category(7, "喜剧片", 1, "", "", "", List.of());
        return new CategoryListResponse(List.of(
                category(1, "电影", 0, "电影频道 &amp; 精选", "电影,在线", "全部电影 &lt;合成&gt;", List.of(action, comedy)),
                category(2, "连续剧", 0, "", "", "", List.of()),
                category(3, "综艺", 0, "", "", "", List.of()),
                category(4, "动漫", 0, "", "", "", List.of()),
                category(5, "纪录片", 0, "", "", "", List.of())));
    }

    public static CategoryRefDto ref(int id, String name, int parentId) {
        return new CategoryRefDto(id, name, "en" + id, parentId);
    }

    public static VideoSummaryDto summary(long id, String name, String pic, CategoryRefDto category) {
        return new VideoSummaryDto(id, name, "", "v" + id, "V", "", category, "", "", pic, "", "", "演员甲", "导演乙",
                "简介", "第" + id + "集", "大陆", "国语", "2026", "", "", "", 0, false, 0, false,
                new BigDecimal("0.0"), 0, 0, 0, 0, 0, 0, 0, UPDATED_AT, UPDATED_AT, false);
    }

    public static List<VideoSummaryDto> summaries(long from, int count, CategoryRefDto category) {
        return LongStream.range(from, from + count)
                .mapToObj(id -> summary(id, "合成视频 " + id, "upload/vod/" + id + ".jpg", category)).toList();
    }

    public static PageResponse<VideoSummaryDto> page(List<VideoSummaryDto> items, int page, int pageSize, long total) {
        return PageResponse.of(items, page, pageSize, total);
    }

    /** 详情：名称、演员、正文中带有合成的恶意片段与实体。 */
    public static VideoDetailDto detail(long id, String name, String pic, String blurb, String content) {
        return new VideoDetailDto(id, name, "副标题 &amp; 特别篇", "v" + id, "V", "#ff0000", ref(6, "动作片", 1),
                "动作,科幻", "标签甲,标签乙", pic, "", "", "演员甲 &amp; 演员乙 <b>粗体</b>", "导演乙", "编剧丙", "",
                blurb, "HD 中字", "2026-09-30", "大陆", "国语", "2026", "", "", "", "", "120 分钟", "", 24, true,
                0, false, new BigDecimal("8.6"), 860, 100, 0, new BigDecimal("7.9"), 1000, 10, 100, 500, 3, 1,
                UPDATED_AT, UPDATED_AT, content);
    }

    public static VideoDetailDto normalDetail() {
        return detail(9, "流浪地球 <script>alert(1)</script> &amp; Tom", "upload/vod/9.jpg",
                "一段  简介 &amp; 剧情\n第二行", "<p onclick=\"steal()\">正文 &amp; <b>重点</b></p>"
                        + "<script>alert('" + LEAK + "')</script>"
                        + "<img src=\"mac://img.maccms.test/a.jpg\" onerror=\"alert(1)\" alt=\"剧照\">"
                        + "<a href=\"javascript:alert(1)\">坏链接</a>"
                        + "<a href=\"https://www.maccms.test/x.html\">外链</a>"
                        + "<iframe src=\"https://evil.test/\"></iframe>");
    }
}

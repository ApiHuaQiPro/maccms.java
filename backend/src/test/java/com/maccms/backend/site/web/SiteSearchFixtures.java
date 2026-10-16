package com.maccms.backend.site.web;

import static com.maccms.backend.site.web.SiteContentFixtures.page;
import static com.maccms.backend.site.web.SiteContentFixtures.ref;
import static com.maccms.backend.site.web.SiteContentFixtures.summaries;
import static com.maccms.backend.site.web.SiteContentFixtures.summary;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.CategoryExtendDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import java.util.List;

/**
 * 搜索页测试用的合成数据（虚构值）。分类 extend 中混入不能作为条件回读的候选值（空项、'0'、首尾空白、逗号外的超长值、
 * 控制字符），页面必须跳过它们。
 */
public final class SiteSearchFixtures {

    /** 合成关键字：不应出现在日志中。 */
    public static final String SECRET_KEYWORD = "合成机密关键字Q7";

    private SiteSearchFixtures() {
    }

    private static CategoryDto category(int id, String name, int parentId, CategoryExtendDto extend,
                                        List<CategoryDto> children) {
        return new CategoryDto(id, name, "en" + id, id, parentId, "", "", "", "", "", extend, children);
    }

    public static CategoryListResponse categories() {
        CategoryExtendDto movie = new CategoryExtendDto("动作,喜剧,剧情/悬疑", "大陆,香港,,0, 美国,a&b", "国语,粤语",
                "2026,2025,20x5", "", "", "", "");
        CategoryExtendDto action = new CategoryExtendDto("动作,科幻", "大陆,台湾", "英语\u0001", "2024", "", "", "", "");
        CategoryExtendDto tv = new CategoryExtendDto("", "日本,韩国," + "长".repeat(21), "日语", "", "", "", "", "");
        return new CategoryListResponse(List.of(
                category(1, "电影", 0, movie, List.of(category(6, "动作片", 1, action, List.of()))),
                category(2, "连续剧", 0, tv, List.of())));
    }

    /** 按条件给出结果：关键字"无结果"→ 空；其余 45 条（3 页）。 */
    public static PageResponse<VideoSummaryDto> answer(VideoListQuery q) {
        if ("无结果".equals(q.filter().keyword())) {
            return page(List.of(), q.page(), q.pageSize(), 0);
        }
        if (q.page() > 3) {
            return page(List.of(), q.page(), q.pageSize(), 45);
        }
        List<VideoSummaryDto> items = q.page() == 1
                ? List.of(summary(9, "命中 <b>" + "&amp; 片</b>", "upload/vod/9.jpg", ref(6, "动作片", 1)))
                : summaries(q.page() * 100L, 20, ref(6, "动作片", 1));
        return page(items, q.page(), q.pageSize(), 45);
    }
}

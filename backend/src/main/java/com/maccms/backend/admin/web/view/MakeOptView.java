package com.maccms.backend.admin.web.view;

import com.maccms.backend.common.lang.LegacyLang;
import java.util.List;

/**
 * 生成静态管理页（旧 admin/view/make/opt.html，B16，25 文档第 8 节）的视图：视频/文章分类、专题与自定义页的下拉项，"全部""当天"按钮用的
 * 编号串。
 *
 * @param makeUrl 旧 {@code url('make')}（按钮把表单提交到这里，带 {@code ?ac=…}）
 */
public record MakeOptView(AdminHead head, LegacyLang lang, String makeUrl, List<Option> vodTypes, String vodTypeIds,
                          String vodTypeIdsToday, List<Option> artTypes, String artTypeIds, String artTypeIdsToday,
                          List<Option> topics, String topicIds, List<Option> labels, String labelIds) {

    /** 下拉项。 */
    public record Option(String value, String label) {
    }
}

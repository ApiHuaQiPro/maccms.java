package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * SSR 搜索页（旧 vod/search 的固定路径 + 查询串形式，MACC-20）。只有公开摘要字段（视频卡片）与安全 URL。
 *
 * @param chrome       页面外壳（noindex；参数不合法时没有 canonical、没有导航）
 * @param heading      h1
 * @param invalid      参数不合法（400）：显示固定提示与空白表单，不回显任何输入
 * @param formAction   搜索表单提交地址（搜索基址，GET）
 * @param keyword      表单回显的关键字（已校验，模板按属性转义），无关键字时为空串
 * @param hiddenFields 提交关键字时保留的当前条件（不含 wd、page，提交后回到第 1 页）
 * @param total        有条件时的结果总数；无条件、参数不合法时没有
 * @param filters      筛选与排序链接组
 * @param videos       当前页的视频；有条件而为空时页面显示"没有找到相关视频"（只会出现在第 1 页）
 * @param pagination   多于一页时的分页
 * @param random       随机排序（by=rnd）：不显示分页，显示固定说明（B05，15 文档第 3.4 节）
 */
public record SearchPage(PageChrome chrome, String heading, boolean invalid, SafeUrl formAction, String keyword,
                         List<HiddenField> hiddenFields, Optional<Long> total, List<FilterGroup> filters,
                         List<VideoCardView> videos, Optional<PaginationView> pagination, boolean random) {
}

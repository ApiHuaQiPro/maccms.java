package com.maccms.backend.content.infrastructure;

import java.util.List;

/**
 * 文章/专题/演员/角色/网址/友链列表的精确筛选条件（契约 1.2.0），由 content.application 从已校验的参数构造；
 * {@code null} 表示该条件未设置。每个 Mapper 只引用本表有的条件，列名写死在 XML 中，值只经 {@code #{}} 绑定。
 *
 * @param letters  首字母（旧 letter：单个字符，或 "0-9" 展开为 10 个数字）
 * @param videoId  角色所属视频 role_rid（旧 rid）
 * @param linkType 友链类型 0 文字、1 图片（旧 type=font/pic）
 */
public record ContentCriteria(Integer level, List<String> letters, String sex, String area, String lang,
                              String starSign, String blood, Long videoId, Integer linkType) {

    public static final ContentCriteria NONE = new ContentCriteria(null, null, null, null, null, null, null, null, null);
}

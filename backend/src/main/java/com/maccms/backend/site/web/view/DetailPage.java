package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 视频公开详情页（旧 vod/detail，数字 id）。只有公开元数据：没有播放/下载地址、剧集、观看密码、积分或会员字段；
 * 播放入口在剧集索引（03 方案 P5）批准前不出现。
 *
 * @param chrome     页面外壳
 * @param title      h1（vod_name，解码一次）
 * @param subTitle   副标题，可为空串
 * @param poster     海报，可无（显示占位）
 * @param breadcrumb 首页 → 分类链 → 当前视频
 * @param metadata   元数据表（只含非空项）
 * @param body       过滤后的正文（vod_content），可为空
 * @param blurb      简介纯文本（vod_blurb，解码一次）；正文为空时显示
 * @param related    同分类最新视频（不含本片）
 * @param favorite   已登录会员的"收藏"表单（B10，19 文档第 5.4 节）；游客为空
 */
public record DetailPage(PageChrome chrome, String title, String subTitle, Optional<SafeUrl> poster,
                         List<NavLink> breadcrumb, List<MetadataItem> metadata, RichTextBody body, String blurb,
                         List<VideoCardView> related, Optional<ContentForm> favorite) {

    public DetailPage(PageChrome chrome, String title, String subTitle, Optional<SafeUrl> poster,
                      List<NavLink> breadcrumb, List<MetadataItem> metadata, RichTextBody body, String blurb,
                      List<VideoCardView> related) {
        this(chrome, title, subTitle, poster, breadcrumb, metadata, body, blurb, related, Optional.empty());
    }

    public DetailPage withFavorite(Optional<ContentForm> form) {
        return new DetailPage(chrome, title, subTitle, poster, breadcrumb, metadata, body, blurb, related, form);
    }
}

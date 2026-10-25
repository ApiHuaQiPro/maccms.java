package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 文章、专题、演员、角色、网址、剧情的详情页（B05，15 文档第 5 节）。正文一律是 RichTextSanitizer 的结果（{@link RichTextBody}），
 * 文本字段已解码一次、由模板转义。
 *
 * @param chrome       页面外壳
 * @param pageClass    根元素的语义 class（article-page、topic-page、actor-page、role-page、website-page、plot-page）
 * @param siteKind     data-site-page 的值
 * @param title        h1
 * @param subTitle     副标题，可为空串
 * @param breadcrumb   面包屑
 * @param image        图片，可无
 * @param metadata     元数据（只含非空项）
 * @param external     外部链接（网址的目标站点，用户点击才访问），可无
 * @param sectionTitle 正文标题（文章当前页标题、剧情当前集名），可为空串
 * @param sectionNote  正文前的说明（文章分页备注），可为空串
 * @param body         过滤后的正文，可为空
 * @param blurb        正文为空时显示的简介纯文本
 * @param pager        文章分页/剧情分集的上一页/下一页（article-pager），可无
 * @param parts        剧情分集列表（plot-list），文章没有
 * @param screenshots  截图（screenshot-list）
 * @param videos       相关视频（专题关联视频）
 * @param cards        相关内容（专题关联文章、视频的角色等）
 * @param cardsTitle   相关内容的标题
 * @param favorite     已登录会员的"收藏"表单（B10：文章、专题、演员，19 文档第 5.4 节）；游客与其他模型为空
 */
public record LibraryDetailPage(PageChrome chrome, String pageClass, String siteKind, String title, String subTitle,
                                List<NavLink> breadcrumb, Optional<SafeUrl> image, List<MetadataItem> metadata,
                                Optional<SafeUrl> external, String sectionTitle, String sectionNote, RichTextBody body,
                                String blurb, Optional<PaginationView> pager, List<NavLink> parts,
                                List<ContentCard> screenshots, List<VideoCardView> videos, List<ContentCard> cards,
                                String cardsTitle, Optional<ContentForm> favorite) {

    public LibraryDetailPage(PageChrome chrome, String pageClass, String siteKind, String title, String subTitle,
                             List<NavLink> breadcrumb, Optional<SafeUrl> image, List<MetadataItem> metadata,
                             Optional<SafeUrl> external, String sectionTitle, String sectionNote, RichTextBody body,
                             String blurb, Optional<PaginationView> pager, List<NavLink> parts,
                             List<ContentCard> screenshots, List<VideoCardView> videos, List<ContentCard> cards,
                             String cardsTitle) {
        this(chrome, pageClass, siteKind, title, subTitle, breadcrumb, image, metadata, external, sectionTitle,
                sectionNote, body, blurb, pager, parts, screenshots, videos, cards, cardsTitle, Optional.empty());
    }

    public LibraryDetailPage withFavorite(Optional<ContentForm> form) {
        return new LibraryDetailPage(chrome, pageClass, siteKind, title, subTitle, breadcrumb, image, metadata, external,
                sectionTitle, sectionNote, body, blurb, pager, parts, screenshots, videos, cards, cardsTitle, form);
    }
}

package com.maccms.backend.content.application;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.ActorSummaryDto;
import com.maccms.backend.content.application.view.ArticleSummaryDto;
import com.maccms.backend.content.application.view.RoleSummaryDto;
import com.maccms.backend.content.application.view.TopicSummaryDto;
import com.maccms.backend.content.application.view.WebsiteSummaryDto;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import org.springframework.stereotype.Service;

/**
 * 文章、专题、演员、角色、网址的 SSR 筛选页（旧 show）与搜索页（旧 search）的只读列表（B05，队长裁定 D-B05-5）。与契约 1.2.0
 * 的列表相同的可见性（分类可见性、发布状态）、分页与排序白名单，另加 {@link LibraryMatch} 的条件；包含匹配条件经
 * {@link ContentReadService#guardedRead} 受搜索开关、并发许可与执行时限约束。不是公开 API：只由 site 调用。
 */
@Service
public class LibrarySearchService {

    private final ArticleReadService articles;
    private final TopicReadService topics;
    private final ActorReadService actors;
    private final RoleReadService roles;
    private final WebsiteReadService websites;
    private final ContentReadService videos;

    public LibrarySearchService(ArticleReadService articles, TopicReadService topics, ActorReadService actors,
                                RoleReadService roles, WebsiteReadService websites, ContentReadService videos) {
        this.articles = articles;
        this.topics = topics;
        this.actors = actors;
        this.roles = roles;
        this.websites = websites;
        this.videos = videos;
    }

    public PageResponse<ArticleSummaryDto> articles(ContentListQuery query, LibraryMatch match) {
        return videos.guardedRead(match, () -> articles.page(query, criteria(query, match)));
    }

    public PageResponse<TopicSummaryDto> topics(ContentListQuery query, LibraryMatch match) {
        return videos.guardedRead(match, () -> topics.page(query, criteria(query, match)));
    }

    public PageResponse<ActorSummaryDto> actors(ContentListQuery query, LibraryMatch match) {
        return videos.guardedRead(match, () -> actors.page(query, criteria(query, match)));
    }

    public PageResponse<RoleSummaryDto> roles(ContentListQuery query, LibraryMatch match) {
        return videos.guardedRead(match, () -> roles.page(query, criteria(query, match)));
    }

    public PageResponse<WebsiteSummaryDto> websites(ContentListQuery query, LibraryMatch match) {
        return videos.guardedRead(match, () -> websites.page(query, criteria(query, match)));
    }

    private static ContentCriteria criteria(ContentListQuery query, LibraryMatch match) {
        return match.apply(query.filter().toCriteria(), query.kind());
    }
}

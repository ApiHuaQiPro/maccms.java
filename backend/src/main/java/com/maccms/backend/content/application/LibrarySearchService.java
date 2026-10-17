package com.maccms.backend.content.application;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.ActorSummaryDto;
import com.maccms.backend.content.application.view.ArticleSummaryDto;
import com.maccms.backend.content.application.view.RoleSummaryDto;
import com.maccms.backend.content.application.view.TopicSummaryDto;
import com.maccms.backend.content.application.view.WebsiteSummaryDto;
import com.maccms.backend.content.infrastructure.SortColumn;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 文章、专题、演员、角色、网址的 SSR 筛选页（旧 show）与搜索页（旧 search）的只读列表（B05，队长裁定 D-B05-5，独立验收返工第 2 项）。
 * 可见性（分类可见性、发布状态）与契约 1.2.0 的列表相同，条件与排序为 {@link LibraryListQuery} 的旧 URL 语义；包含匹配条件经
 * {@link ContentReadService#guardedRead} 受搜索开关、并发许可与执行时限约束。不是公开 API：只由 site 调用。
 *
 * <p>随机排序（旧 {@code by=rnd}，Art.php:357-367、Topic.php、Actor.php、Role.php、Website.php 的同一段）：先按全部条件计数 c，
 * 随机页 = {@code mt_rand(1, floor(c / 每页) + 1)}（{@link VideoRandom#pageBetweenOneAnd}），忽略请求的页码，按 hits_week 倒序
 * 取该页（追加主键倒序，旧版没有），total 为 c；c 恰为每页条数的整数倍时最后一个随机页为空（与旧版相同）。这几个模型只有这一种
 * 算法（视频另有计数大于 2000 时的候选算法，见 {@link ContentReadService}）。
 */
@Service
public class LibrarySearchService {

    private final ArticleReadService articles;
    private final TopicReadService topics;
    private final ActorReadService actors;
    private final RoleReadService roles;
    private final WebsiteReadService websites;
    private final ContentReadService videos;
    private final VideoRandom random;

    @Autowired
    public LibrarySearchService(ArticleReadService articles, TopicReadService topics, ActorReadService actors,
                                RoleReadService roles, WebsiteReadService websites, ContentReadService videos,
                                ObjectProvider<VideoRandom> random) {
        this(articles, topics, actors, roles, websites, videos, random.getIfAvailable(VideoRandom::new));
    }

    LibrarySearchService(ArticleReadService articles, TopicReadService topics, ActorReadService actors,
                         RoleReadService roles, WebsiteReadService websites, ContentReadService videos,
                         VideoRandom random) {
        this.articles = articles;
        this.topics = topics;
        this.actors = actors;
        this.roles = roles;
        this.websites = websites;
        this.videos = videos;
        this.random = random;
    }

    public PageResponse<ArticleSummaryDto> articles(LibraryListQuery query) {
        expect(query, ContentKind.ARTICLE);
        return videos.guardedRead(query.match(), () -> list(query, articles.slice(query.categoryId(), query.criteria())));
    }

    public PageResponse<TopicSummaryDto> topics(LibraryListQuery query) {
        expect(query, ContentKind.TOPIC);
        return videos.guardedRead(query.match(), () -> list(query, topics.slice(query.criteria())));
    }

    public PageResponse<ActorSummaryDto> actors(LibraryListQuery query) {
        expect(query, ContentKind.ACTOR);
        return videos.guardedRead(query.match(), () -> list(query, actors.slice(query.categoryId(), query.criteria())));
    }

    public PageResponse<RoleSummaryDto> roles(LibraryListQuery query) {
        expect(query, ContentKind.ROLE);
        return videos.guardedRead(query.match(), () -> list(query, roles.slice(query.criteria())));
    }

    public PageResponse<WebsiteSummaryDto> websites(LibraryListQuery query) {
        expect(query, ContentKind.WEBSITE);
        return videos.guardedRead(query.match(),
                () -> list(query, websites.slice(query.categoryId(), query.criteria())));
    }

    private <T> PageResponse<T> list(LibraryListQuery query, LibrarySlice<T> slice) {
        if (!query.random()) {
            return LibrarySlice.page(slice, query.sort(), query.descending(), query.page(), query.pageSize());
        }
        long count = slice.count();
        long page = random.pageBetweenOneAnd(count / query.pageSize() + 1);
        long offset = (page - 1) * query.pageSize();
        List<T> rows = count == 0 || offset >= count ? List.of()
                : slice.rows(SortColumn.HITS_WEEK, true, offset, query.pageSize());
        return PageResponse.of(rows, Math.toIntExact(page), query.pageSize(), count);
    }

    private static void expect(LibraryListQuery query, ContentKind kind) {
        if (query.kind() != kind) {
            throw new IllegalArgumentException("kind");
        }
    }
}

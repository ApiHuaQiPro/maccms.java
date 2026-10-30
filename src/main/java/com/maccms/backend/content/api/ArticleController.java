package com.maccms.backend.content.api;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentKind;
import com.maccms.backend.content.application.ContentListQuery;
import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.view.ArticleDetailDto;
import com.maccms.backend.content.application.view.ArticleSummaryDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约 1.2.0：/api/v1/article-categories、/api/v1/articles、/api/v1/articles/{id}。
 * 列表参数与 1.0.0 既有参数相同的读取方式（多次出现时逗号连接后校验），由 {@link ContentListQuery#parse} 统一校验。
 */
@RestController
@RequestMapping("/api/v1")
public class ArticleController {

    private final ArticleReadService service;

    public ArticleController(ArticleReadService service) {
        this.service = service;
    }

    @GetMapping("/article-categories")
    public CategoryListResponse categories() {
        return service.listCategories();
    }

    @GetMapping("/articles")
    public PageResponse<ArticleSummaryDto> list(HttpServletRequest request) {
        RawQueryParams params = RawQueryParams.of(request);
        return service.listArticles(ContentListQuery.parse(ContentKind.ARTICLE, params::joined));
    }

    @GetMapping("/articles/{id}")
    public ArticleDetailDto detail(@PathVariable String id) {
        return service.getArticle(VideoId.parse(id));
    }
}

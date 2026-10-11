package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/** 分页（site/components/pagination.jte 的参数）；只有一页时页面模型里没有分页。 */
public record PaginationView(int currentPage, int pageCount, Optional<SafeUrl> previousUrl,
                             Optional<SafeUrl> nextUrl) {
}

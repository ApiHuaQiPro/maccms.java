package com.maccms.backend.site.web;

import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 骨架验收用的占位页（MACC-9）：验证 jte 渲染、站点配置、canonical、维护关闭与错误页链路。
 * 不是首页、分类页或详情页，不读取任何内容数据；noindex。三页实现另行任务。
 */
@Controller
@ConditionalOnSiteEnabled
public class SitePlaceholderController {

    private final SiteHtmlRenderer renderer;
    private final SitePages pages;

    public SitePlaceholderController(SiteHtmlRenderer renderer, SitePages pages) {
        this.renderer = renderer;
        this.pages = pages;
    }

    @GetMapping("/" + SitePages.PLACEHOLDER_PATH)
    public ResponseEntity<String> placeholder() {
        return renderer.ok(t -> t.sitePagesPlaceholder(pages.placeholder()), true);
    }
}

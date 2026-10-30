package com.maccms.backend.site.web;

import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.site.application.SearchTimespan;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 搜索频率限制（B08，17 文档第 2.5 节）：旧 {@code check_search} 的 {@code page==1 && mac_get_time_span("last_searchtime") <
 * search_timespan}（{@code index/controller/Base.php:59-62}）。按会话计时，读改写在会话上原子完成（同一会话的并发第 1 页搜索只有一个
 * 放行，与旧版会话文件锁排队的结果相同）；被拒绝的请求同样刷新计时（旧版先写会话再比较），所以连续过快的请求会一直被拒绝，直到停下
 * 至少 {@code search_timespan} 秒。
 *
 * <p>与旧版的差异：拒绝时返回 429 + {@code Retry-After} 的提示页（旧版是 200 的跳转提示页）；参数不合法（400）与不认识的地址（404）
 * 不计时（旧版的路由没有这两种结果）；未配置限制时不建会话（旧版即使值为 0 也会开启会话）。
 */
public final class SearchThrottle {

    static final String KEY = "last_searchtime";

    private final SiteSessions sessions;
    private final SearchTimespan timespan;
    private final SiteHtmlRenderer renderer;
    private final SitePages pages;

    public SearchThrottle(SiteSessions sessions, SearchTimespan timespan, SiteHtmlRenderer renderer, SitePages pages) {
        this.sessions = sessions;
        this.timespan = timespan;
        this.renderer = renderer;
        this.pages = pages;
    }

    /** 第 1 页时计时；过于频繁时返回 429 页面，否则 null。 */
    ResponseEntity<String> check(int page, HttpServletRequest request, HttpServletResponse response) {
        if (page != 1 || !timespan.enabled()) {
            return null;
        }
        SessionState session = sessions.currentOrCreate(request, response);
        long span = session.timeSpan(KEY, sessions.nowEpochSeconds());
        if (!timespan.tooSoon(span)) {
            return null;
        }
        return renderer.error(HttpStatus.TOO_MANY_REQUESTS, pages.tooFrequent(timespan.message()),
                Duration.ofSeconds(timespan.retryAfterSeconds()));
    }
}

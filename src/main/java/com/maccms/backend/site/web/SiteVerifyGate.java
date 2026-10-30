package com.maccms.backend.site.web;

import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.site.web.view.MemberCaptcha;
import com.maccms.backend.site.web.view.VerifyPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 搜索页、筛选页的验证码门（B11，旧 {@code check_search}/{@code check_show}，application/index/controller/Base.php:35-42、63-70）：旧配置
 * {@code search_verify}/{@code show_verify} 打开、本会话还没有 {@code search_verify}/{@code show_verify} 标记时，页面换成验证码页
 * （{@code mac_no_cahche()}，新版 no-store）。标记由 {@code ajax/verify_check} 写入。
 */
@Component
@ConditionalOnSiteEnabled
public class SiteVerifyGate {

    private static final String VERIFY_PATH = "index.php/verify/index.html";

    private final SiteSettings site;
    private final SitePages pages;
    private final LegacyUrlBuilder urls;
    private final SiteHtmlRenderer renderer;
    private final SiteSessions sessions;

    public SiteVerifyGate(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer, SiteSessions sessions) {
        this.site = pages.site();
        this.pages = pages;
        this.urls = urls;
        this.renderer = renderer;
        this.sessions = sessions;
    }

    /**
     * @param type {@code search} 或 {@code show}
     * @return 本会话未通过时的验证码页；已通过时为空
     */
    public Optional<ResponseEntity<String>> check(HttpServletRequest request, String type) {
        SessionState s = sessions.current(request);
        if (s != null && s.get(type + "_verify", String.class).isPresent()) {
            return Optional.empty();
        }
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        String query = request.getQueryString();
        String back = raw.substring(1) + (query == null || query.isEmpty() ? "" : "?" + query);
        VerifyPage page = new VerifyPage(pages.member("安全验证"), "安全验证",
                site.publicPath(urls.action("ajax/verify_check", Map.of())), type, back,
                new MemberCaptcha(site.publicPath(VERIFY_PATH)));
        return Optional.of(renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesVerify(page)));
    }
}

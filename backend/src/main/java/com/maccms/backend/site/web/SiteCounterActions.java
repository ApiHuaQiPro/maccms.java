package com.maccms.backend.site.web;

import com.maccms.backend.comment.application.CommentService;
import com.maccms.backend.comment.application.view.CommentCounts;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.content.application.ContentCounterService;
import com.maccms.backend.content.application.CounterTarget;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.view.CounterOutcome;
import com.maccms.backend.content.application.view.DiggCounts;
import com.maccms.backend.content.application.view.HitCounts;
import com.maccms.backend.content.application.view.RefererCounts;
import com.maccms.backend.content.application.view.ScoreCounts;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.site.application.LegacyParams;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.UserJumpPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 旧 {@code index/controller/Ajax.php} 的计数与验证码接口（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 5、7 节）：
 * {@code hits}、{@code digg}、{@code score}、{@code referer}（参数按 {@code mac_param_url}，即 {@link LegacyParams}）与 {@code verify_check}
 * （参数按 {@code input()}）。响应与旧版逐字节相同（JSON）；非 ajax 的 POST（新版内容页上的无脚本表单）返回提示页（D-B11-10）。
 * 写入被数据库拒绝（计数已到列上限等，旧版 500）时返回 {@code {"code":1002,"msg":"保存失败!："}}，不写入（D-B11-5）。
 */
final class SiteCounterActions {

    /** 旧会话里 {@code show_verify}、{@code search_verify} 的键（ThinkPHP {@code session($type.'_verify', '1')}，Ajax.php:470）。 */
    static final String SHOW_VERIFY = "show_verify";
    static final String SEARCH_VERIFY = "search_verify";
    private static final Set<Long> DIGG_MIDS = Set.of(1L, 2L, 3L, 4L, 8L, 9L, 11L);
    private static final int DIGG_COOKIE_SECONDS = 30;
    private static final Logger log = LoggerFactory.getLogger(SiteCounterActions.class);

    private final SiteSettings site;
    private final SitePages pages;
    private final SiteHtmlRenderer renderer;
    private final ObjectProvider<SiteSessions> sessions;
    private final ObjectProvider<ClientIpResolver> clientIp;
    private final ObjectProvider<ContentCounterService> counters;
    private final ObjectProvider<CommentService> comments;
    private final ObjectProvider<MemberReferralService> referral;
    private final LegacyConfigService legacy;

    /**
     * 依赖都按需取得：只装配了部分 Bean 的测试中缺少某个服务时，对应的操作按 404（与 B11 之前相同），可写数据源关闭时按 503。
     */
    SiteCounterActions(SitePages pages, SiteHtmlRenderer renderer, ObjectProvider<SiteSessions> sessions,
                       ObjectProvider<ClientIpResolver> clientIp,
                       ObjectProvider<ContentCounterService> counters, ObjectProvider<CommentService> comments,
                       ObjectProvider<MemberReferralService> referral, LegacyConfigService legacy) {
        this.site = pages.site();
        this.pages = pages;
        this.renderer = renderer;
        this.sessions = sessions;
        this.clientIp = clientIp;
        this.counters = counters;
        this.comments = comments;
        this.referral = referral;
        this.legacy = legacy;
    }

    // ------------------------------------------------------------------ hits（Ajax.php:152-217）

    ResponseEntity<?> hits(HttpServletRequest request, LegacyParams p, boolean ajax) {
        String id = p.text("id").orElse("");
        Optional<CounterTarget> target = CounterTarget.ofMid(p.intval("mid"));
        if (phpEmpty(id) || target.isEmpty()) {
            return respond(request, ajax, SiteRequests.reply(1001, "参数错误"), List.of());
        }
        ContentCounterService c = required(counters);
        boolean update = p.text("type").orElse("").equals("update");
        if (update && !c.writable()) {
            return SiteRequests.unavailableJson("计数功能暂未开放");
        }
        long rid = PhpIntval.of(id);
        CounterOutcome<HitCounts> o = update ? c.hit(target.get(), rid) : c.hits(target.get(), rid);
        if (o.status() != CounterOutcome.Status.FOUND) {
            return respond(request, ajax, failure(o.status()), List.of());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", o.counts().hits());
        data.put("hits_day", o.counts().hitsDay());
        data.put("hits_week", o.counts().hitsWeek());
        data.put("hits_month", o.counts().hitsMonth());
        return respond(request, ajax, ok("ok", data), List.of());
    }

    // ------------------------------------------------------------------ digg（Ajax.php:302-345）

    ResponseEntity<?> digg(HttpServletRequest request, LegacyParams p, boolean ajax) {
        String id = p.text("id").orElse("");
        long mid = p.intval("mid");
        if (phpEmpty(id) || !DIGG_MIDS.contains(mid)) {
            return respond(request, ajax, SiteRequests.reply(1001, "参数错误"), List.of());
        }
        String type = p.text("type").orElse("");
        boolean up = type.equals("up");
        boolean count = up || type.equals("down");
        long rid = PhpIntval.of(id);
        String prefix = mid == 4 ? "comment" : CounterTarget.ofMid(mid).orElseThrow().prefix();
        List<String> set = List.of();
        if (!phpEmpty(type)) {
            String cookie = prefix + "-digg-" + id;
            if (SiteRequests.cookieSet(SiteRequests.cookies(request), cookie)) {
                return respond(request, ajax, SiteRequests.reply(1002, "您已参与过了！"), List.of());
            }
            if (count && SiteRequests.validCookieName(cookie)) {
                set = List.of(PhpCookies.setCookie(cookie, "t", now() + DIGG_COOKIE_SECONDS, now(), "/"));
            }
        }
        long upCount;
        long downCount;
        if (mid == 4) {
            CommentService s = required(comments);
            if (count && !s.writable()) {
                return SiteRequests.unavailableJson("计数功能暂未开放");
            }
            CommentCounts cc = count ? s.digg(rid, up) : s.counts(rid);
            if (cc.rejected()) {
                return respond(request, ajax, failure(CounterOutcome.Status.REJECTED), List.of());
            }
            if (!cc.found()) {
                return respond(request, ajax, failure(CounterOutcome.Status.MISSING), set);
            }
            upCount = cc.up();
            downCount = cc.down();
        } else {
            ContentCounterService c = required(counters);
            if (count && !c.writable()) {
                return SiteRequests.unavailableJson("计数功能暂未开放");
            }
            CounterTarget t = CounterTarget.ofMid(mid).orElseThrow();
            CounterOutcome<DiggCounts> o = count ? c.digg(t, rid, up) : c.digg(t, rid);
            if (o.status() == CounterOutcome.Status.REJECTED) {
                return respond(request, ajax, failure(o.status()), List.of());
            }
            if (o.status() == CounterOutcome.Status.MISSING) {
                return respond(request, ajax, failure(o.status()), set);
            }
            upCount = o.counts().up();
            downCount = o.counts().down();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("up", upCount);
        data.put("down", downCount);
        return respond(request, ajax, ok("ok", data), set);
    }

    // ------------------------------------------------------------------ score（Ajax.php:347-398）

    ResponseEntity<?> score(HttpServletRequest request, LegacyParams p, boolean ajax) {
        String id = p.text("id").orElse("");
        Optional<CounterTarget> target = CounterTarget.ofMid(p.intval("mid"));
        if (phpEmpty(id) || target.isEmpty()) {
            return respond(request, ajax, SiteRequests.reply(1001, "参数错误"), List.of());
        }
        ContentCounterService c = required(counters);
        long rid = PhpIntval.of(id);
        CounterOutcome<ScoreCounts> read = c.score(target.get(), rid);
        if (read.status() != CounterOutcome.Status.FOUND) {
            return respond(request, ajax, failure(read.status()), List.of());
        }
        long score = p.intval("score");
        CounterOutcome<ScoreCounts> o = read;
        List<String> set = List.of();
        if (score != 0) {
            String cookie = target.get().prefix() + "-score-" + id;
            if (SiteRequests.cookieSet(SiteRequests.cookies(request), cookie)) {
                return respond(request, ajax, SiteRequests.reply(1002, "您已参与过了！"), List.of());
            }
            if (!c.writable()) {
                return SiteRequests.unavailableJson("计数功能暂未开放");
            }
            o = c.score(target.get(), rid, score);
            if (o.status() != CounterOutcome.Status.FOUND) {
                return respond(request, ajax, failure(o.status()), List.of());
            }
            if (SiteRequests.validCookieName(cookie)) {
                set = List.of(PhpCookies.setCookie(cookie, "t", now() + DIGG_COOKIE_SECONDS, now(), "/"));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("score", o.counts().score());
        data.put("score_num", o.counts().scoreNum());
        data.put("score_all", o.counts().scoreAll());
        return respond(request, ajax, ok("感谢您的参与，评分成功", data), set);
    }

    // ------------------------------------------------------------------ referer（Ajax.php:219-300）

    ResponseEntity<?> referer(HttpServletRequest request, LegacyParams p, boolean ajax) {
        String url = p.text("url").orElse("");
        String domain = p.text("domain").orElse("");
        if (phpEmpty(url)) {
            return respond(request, ajax, SiteRequests.reply(1001, "参数错误"), List.of());
        }
        String referer = request.getHeader(HttpHeaders.REFERER);
        String host = request.getHeader(HttpHeaders.HOST);
        if (host == null) {
            host = "";
        }
        if (referer == null || (!host.isEmpty() && !referer.contains(host))) {
            return respond(request, ajax, SiteRequests.reply(1002, "参数错误"), List.of());
        }
        // 旧版 strpos($url, $domain)：PHP 8 下空域名返回 0（通过，随后按 http:// 前缀匹配到任意网址），PHP 7.3 返回 false；新版按 7.3
        if (domain.isEmpty() || !url.contains(domain)) {
            return respond(request, ajax, SiteRequests.reply(1003, "参数错误"), List.of());
        }
        ContentCounterService c = required(counters);
        MemberReferralService r = required(referral);
        if (!c.writable() || !r.available()) {
            return SiteRequests.unavailableJson("计数功能暂未开放");
        }
        CounterOutcome<RefererCounts> site = c.refererSite(domain);
        if (site.status() != CounterOutcome.Status.FOUND) {
            return respond(request, ajax, failure(site.status()), List.of());
        }
        int visit = r.siteVisit(required(clientIp).resolve(request), url, referVisitNum());
        if (visit == 102) {
            return respond(request, ajax, SiteRequests.reply(102, "每日仅能%s次来路记录"), List.of());
        }
        if (visit != 1) {
            return respond(request, ajax, SiteRequests.reply(103, "来路记录失败，请重试"), List.of());
        }
        CounterOutcome<RefererCounts> o = p.text("type").orElse("").equals("update") ? c.referer(domain) : site;
        if (o.status() != CounterOutcome.Status.FOUND) {
            return respond(request, ajax, failure(o.status()), List.of());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("referer", o.counts().referer());
        data.put("referer_day", o.counts().refererDay());
        data.put("referer_week", o.counts().refererWeek());
        data.put("referer_month", o.counts().refererMonth());
        return respond(request, ajax, ok("ok", data), List.of());
    }

    /** 旧 {@code $GLOBALS['config']['website']['refer_visit_num']}，为空时 1（Website.php:616-619）。 */
    private long referVisitNum() {
        if (legacy == null || !legacy.isConfigured()) {
            return 1;
        }
        try {
            PhpValue v = legacy.read(LegacyConfigFile.MACCMS).path("website", "refer_visit_num");
            String s = com.maccms.backend.legacyconfig.application.view.PhpValues.text(v);
            long n = PhpIntval.of(s);
            return com.maccms.backend.legacyconfig.application.view.PhpValues.empty(v) ? 1 : n;
        } catch (LegacyConfigException e) {
            log.warn("refer_visit_num unreadable, using 1: {}", SafeExceptionLog.typeChain(e));
            return 1;
        }
    }

    // ------------------------------------------------------------------ verify_check（Ajax.php:460-472）

    /**
     * 参数按 {@code input()}（{@code type} 原值精确比较，{@code verify} 按默认槽校验，通过或猜错都作废，D-B08-4）。通过后本会话的
     * {@code <type>_verify} 置 1，之后的搜索页/筛选页不再要求验证码（直到会话失效）。
     *
     * @param back 新版验证码页提交的本页地址（非 ajax 时提示页的"继续"）；不是站内规范路径时不给链接
     */
    ResponseEntity<?> verifyCheck(HttpServletRequest request, HttpServletResponse response, String type,
                                  String verify, String back, boolean ajax) {
        if (!SEARCH_VERIFY.equals(type + "_verify") && !SHOW_VERIFY.equals(type + "_verify")) {
            return respond(request, ajax, true, SiteRequests.reply(1001, "参数错误"), List.of(), Optional.empty());
        }
        SiteSessions ss = required(sessions);
        SessionState s = ss.current(request);
        if (!Captchas.check(s, verify, Captchas.DEFAULT_SLOT, ss.nowEpochSeconds())) {
            return respond(request, ajax, true, SiteRequests.reply(1002, "验证码错误"), List.of(), Optional.empty());
        }
        s.put(type + "_verify", "1");
        return respond(request, ajax, true, SiteRequests.reply(1, "ok"), List.of(), backLink(back));
    }

    private Optional<SafeUrl> backLink(String back) {
        if (back == null || back.isEmpty() || back.length() > 2048) {
            return Optional.empty();
        }
        String path = back.startsWith("/") ? back.substring(1) : back;
        int q = path.indexOf('?');
        String bare = q < 0 ? path : path.substring(0, q);
        String query = q < 0 ? "" : path.substring(q);
        if (path.contains("//") || path.contains("\\") || query.contains("#")) {
            return Optional.empty();
        }
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '"' || c == '<' || c == '>') {
                return Optional.empty();
            }
        }
        try {
            // 只认站内的规范路径（SiteSettings.publicPath 校验），查询串只能是已编码的可见 ASCII
            return SafeUrl.parse(site.publicPath(bare).href() + query);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 响应

    private ResponseEntity<?> respond(HttpServletRequest request, boolean ajax, Map<String, Object> body,
                                      List<String> cookies) {
        return respond(request, ajax, false, body, cookies, Optional.empty());
    }

    /**
     * ajax 请求、或不是 POST 的请求：JSON（旧版 {@code json()}）；非 ajax 的 POST：提示页。{@code pageForAnyNonAjax} 为真时（verify_check）
     * 非 ajax 的请求一律给提示页（旧版的数组返回值在非 ajax 时是 500）。
     */
    private ResponseEntity<?> respond(HttpServletRequest request, boolean ajax, boolean pageForAnyNonAjax,
                                      Map<String, Object> body, List<String> cookies, Optional<SafeUrl> back) {
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        if (ajax || (!post && !pageForAnyNonAjax)) {
            return SiteUserController.withLabelUserCookies(SiteRequests.json(body, cookies),
                    SiteMemberInterceptor.member(request).setCookies());
        }
        boolean ok = ((Integer) body.get("code")) == 1;
        UserJumpPage page = new UserJumpPage(pages.member(ok ? "操作成功" : "操作失败"), ok, (String) body.get("msg"),
                back.isPresent() ? back : Optional.of(site.homePath()));
        ResponseEntity<String> r = renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesUserJump(page));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders());
        for (String c : cookies) {
            b.header(HttpHeaders.SET_COOKIE, c);
        }
        return SiteUserController.withLabelUserCookies(b.body(r.getBody()),
                SiteMemberInterceptor.member(request).setCookies());
    }

    private static Map<String, Object> ok(String msg, Map<String, Object> data) {
        Map<String, Object> m = SiteRequests.reply(1, msg);
        m.put("data", data);
        return m;
    }

    private static Map<String, Object> failure(CounterOutcome.Status status) {
        return status == CounterOutcome.Status.REJECTED ? SiteRequests.reply(1002, "保存失败!：")
                : SiteRequests.reply(1002, "获取失败");
    }

    private long now() {
        return required(counters).now();
    }

    /** 缺少某个服务（只装配了部分 Bean 的测试）时按 404。 */
    private static <T> T required(ObjectProvider<T> p) {
        T v = p.getIfAvailable();
        if (v == null) {
            throw new com.maccms.backend.common.web.ApiException(com.maccms.backend.common.web.ErrorCode.NOT_FOUND,
                    "页面不存在");
        }
        return v;
    }

    private static boolean phpEmpty(String s) {
        return s.isEmpty() || s.equals("0");
    }
}

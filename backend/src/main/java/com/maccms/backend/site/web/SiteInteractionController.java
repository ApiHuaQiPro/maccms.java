package com.maccms.backend.site.web;

import com.maccms.backend.comment.application.CommentService;
import com.maccms.backend.comment.application.view.BoardPage;
import com.maccms.backend.comment.application.view.BoardSettings;
import com.maccms.backend.comment.application.view.CommentCounts;
import com.maccms.backend.comment.application.view.CommentItem;
import com.maccms.backend.comment.application.view.GbookItem;
import com.maccms.backend.comment.application.view.InteractionSettings;
import com.maccms.backend.comment.application.view.PostReply;
import com.maccms.backend.comment.application.view.Poster;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.site.application.LegacyParams;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.site.web.view.BoardList;
import com.maccms.backend.site.web.view.BoardPostForm;
import com.maccms.backend.site.web.view.CommentEntry;
import com.maccms.backend.site.web.view.CommentPage;
import com.maccms.backend.site.web.view.GbookEntry;
import com.maccms.backend.site.web.view.GbookPage;
import com.maccms.backend.site.web.view.HiddenField;
import com.maccms.backend.site.web.view.InlineForm;
import com.maccms.backend.site.web.view.MemberCaptcha;
import com.maccms.backend.site.web.view.PaginationView;
import com.maccms.backend.site.web.view.UserJumpPage;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 旧前台 {@code index/controller/Comment.php} 与 {@code Gbook.php}（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 3、4 节）：
 * 评论页与片段（{@code comment/index}、{@code comment/ajax}）、发表（{@code comment/saveData} 与 POST {@code comment/index}）、举报、顶踩；
 * 留言页与片段（{@code gbook/index}、{@code gbook/ajax}）、报错页（{@code gbook/report}）、发表（{@code gbook/saveData} 与 POST
 * {@code gbook/index}）。业务规则在 {@link CommentService}；这里负责地址、参数、cookie、验证码会话与输出。
 *
 * <p>地址：{@code /index.php/<comment|gbook>/<操作>[.html][/键/值…]}（{@code rewrite} 打开时另认去掉 {@code /index.php} 的形态；
 * {@code route_status} 打开时另认旧路由 {@code gbook}、{@code gbook-<页码>}，application/route.php:131-149）。操作名不分大小写（ThinkPHP
 * {@code url_convert}）。ajax 请求的响应与旧版逐字节相同；非 ajax 的发表、举报、顶踩返回提示页（旧版：发表时 500、举报与顶踩照样是 JSON，
 * 新版页面不带脚本、由表单提交，D-B11-10）。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteInteractionController {

    private static final Logger log = LoggerFactory.getLogger(SiteInteractionController.class);
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(?:html|htm|shtm|shtml|xml)$");
    private static final Pattern GBOOK_ALIAS = Pattern.compile("gbook(?:-([0-9]+))?");
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String VERIFY_PATH = "index.php/verify/index.html";
    private static final int MAX_PAGE = 100_000;

    private final SiteSettings site;
    private final SitePages pages;
    private final LegacyUrlBuilder urls;
    private final SiteHtmlRenderer renderer;
    private final SiteSessions sessions;
    private final ClientIpResolver clientIp;
    private final ObjectProvider<CommentService> comments;
    private final ObjectProvider<MemberAccountService> accounts;

    public SiteInteractionController(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer,
                                     SiteSessions sessions, ClientIpResolver clientIp,
                                     ObjectProvider<CommentService> comments,
                                     ObjectProvider<MemberAccountService> accounts) {
        this.site = pages.site();
        this.pages = pages;
        this.urls = urls;
        this.renderer = renderer;
        this.sessions = sessions;
        this.clientIp = clientIp;
        this.comments = comments;
        this.accounts = accounts;
    }

    @RequestMapping(value = {"/index.php/comment", "/index.php/comment/**", "/comment", "/comment/**",
            "/index.php/gbook", "/index.php/gbook/**", "/gbook", "/gbook/**", "/index.php/gbook.html",
            "/index.php/gbook-*", "/gbook.html", "/gbook-*"},
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<?> handle(HttpServletRequest request) {
        Route route = route(request);
        Map<String, PhpInput.Value> params = SiteRequests.input(request, route.pathVars());
        boolean ajax = SiteRequests.isAjax(request, params);
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        CommentService service = comments.getIfAvailable();
        if (service == null) {
            return unavailable(ajax);
        }
        InteractionSettings settings;
        try {
            settings = service.settings();
        } catch (LegacyConfigException e) {
            log.error("interaction settings unreadable: {}", SafeExceptionLog.typeChain(e));
            return renderer.error(HttpStatus.SERVICE_UNAVAILABLE, pages.unavailable(), java.time.Duration.ofSeconds(5));
        }
        boolean comment = route.controller().equals("comment");
        BoardSettings board = comment ? settings.comment() : settings.gbook();
        if (!board.open()) {
            // 旧构造函数：echo 'comment is close' / 'gbook is close'; exit;（Comment.php:11-15、Gbook.php:9-13）
            return SiteRequests.text(comment ? "comment is close" : "gbook is close");
        }
        if (comment) {
            return switch (route.action()) {
                case "index" -> post ? save(request, service, settings, params, ajax, true)
                        : commentPage(service, board, params, false);
                case "ajax" -> commentPage(service, board, params, true);
                case "savedata" -> save(request, service, settings, params, ajax, true);
                case "report" -> report(request, service, board, params, ajax, post);
                case "digg" -> digg(request, service, board, params, ajax, post);
                default -> throw notFound();
            };
        }
        return switch (route.action()) {
            case "index" -> post ? save(request, service, settings, params, ajax, false)
                    : gbookPage(service, board, params, false);
            case "ajax" -> gbookPage(service, board, params, true);
            case "report" -> reportPage(service, board, params);
            case "savedata" -> save(request, service, settings, params, ajax, false);
            default -> throw notFound();
        };
    }

    // ------------------------------------------------------------------ 发表

    private ResponseEntity<?> save(HttpServletRequest request, CommentService service, InteractionSettings settings,
                                   Map<String, PhpInput.Value> params, boolean ajax, boolean comment) {
        if (!service.writable()) {
            return ajax ? SiteRequests.unavailableJson("评论留言功能暂未开放") : unavailable(false);
        }
        Map<String, PhpCookies.Value> cookies = SiteRequests.cookies(request);
        String cookieName = comment ? "comment_timespan" : "gbook_timespan";
        Poster poster = poster(request);
        long ip = legacyIp(clientIp.resolve(request));
        java.util.function.BooleanSupplier captcha = () -> {
            byte[] v = PhpInput.scalar(params, "verify");
            return Captchas.check(sessions.current(request), v == null ? null : new String(v, StandardCharsets.UTF_8),
                    Captchas.DEFAULT_SLOT, sessions.nowEpochSeconds());
        };
        PostReply reply = comment
                ? service.postComment(settings, params, poster, SiteRequests.cookieSet(cookies, cookieName), captcha, ip)
                : service.postGbook(settings, params, poster, SiteRequests.cookieSet(cookies, cookieName), captcha, ip);
        List<String> set = new ArrayList<>();
        if (reply.written()) {
            BoardSettings b = comment ? settings.comment() : settings.gbook();
            set.add(timespanCookie(cookieName, b, service.now()));
        }
        if (ajax) {
            return withLabelUser(request, SiteRequests.json(SiteRequests.reply(reply.code(), reply.msg()), set));
        }
        Optional<SafeUrl> back = comment ? commentBack(params) : Optional.of(site.publicPath(
                urls.action("gbook/index", Map.of())));
        return jump(request, reply.code() == 1, reply.msg(), back, set);
    }

    // ------------------------------------------------------------------ 评论举报与顶踩

    /** Comment.php:119-138。参数取 {@code input()}，{@code id} 为 intval。 */
    private ResponseEntity<?> report(HttpServletRequest request, CommentService service, BoardSettings board,
                                     Map<String, PhpInput.Value> params, boolean ajax, boolean post) {
        long id = intval(params, "id");
        if (id == 0) {
            return answer(request, ajax, post, SiteRequests.reply(1001, "参数错误"), List.of(), commentBack(params));
        }
        String cookie = "comment-report-" + id;
        if (SiteRequests.cookieSet(SiteRequests.cookies(request), cookie)) {
            return answer(request, ajax, post, SiteRequests.reply(1002, "您已参与过了！"), List.of(), commentBack(params));
        }
        if (!service.writable()) {
            return ajax ? SiteRequests.unavailableJson("评论留言功能暂未开放") : unavailable(false);
        }
        if (!service.report(id)) {
            return answer(request, ajax, post, SiteRequests.reply(1002, "保存失败!："), List.of(), commentBack(params));
        }
        return answer(request, ajax, post, SiteRequests.reply(1, "操作成功"),
                List.of(timespanCookie(cookie, board, service.now())), commentBack(params));
    }

    /** Comment.php:140-182：{@code type} 为真值时检查并写 cookie（任何真值），只有 up/down 计数。 */
    private ResponseEntity<?> digg(HttpServletRequest request, CommentService service, BoardSettings board,
                                   Map<String, PhpInput.Value> params, boolean ajax, boolean post) {
        long id = intval(params, "id");
        byte[] typeRaw = PhpInput.scalar(params, "type");
        PhpInput.Value typeValue = params.get("type");
        boolean typeSet = typeValue != null && (typeValue.array() || !SiteRequests.phpEmpty(typeValue.bytes()));
        if (id == 0 || !typeSet) {
            return answer(request, ajax, post, SiteRequests.reply(1001, "参数错误"), List.of(), commentBack(params));
        }
        String type = typeRaw == null ? "" : new String(typeRaw, StandardCharsets.ISO_8859_1);
        String cookie = "comment-digg-" + id;
        if (SiteRequests.cookieSet(SiteRequests.cookies(request), cookie)) {
            return answer(request, ajax, post, SiteRequests.reply(1002, "您已参与过了！"), List.of(), commentBack(params));
        }
        if (!service.writable()) {
            return ajax ? SiteRequests.unavailableJson("评论留言功能暂未开放") : unavailable(false);
        }
        CommentCounts counts = type.equals("up") ? service.digg(id, true)
                : type.equals("down") ? service.digg(id, false) : service.counts(id);
        if (counts.rejected()) {
            return answer(request, ajax, post, SiteRequests.reply(1002, "保存失败!："), List.of(), commentBack(params));
        }
        List<String> set = List.of(timespanCookie(cookie, board, service.now()));
        if (!counts.found()) {
            return answer(request, ajax, post, SiteRequests.reply(1002, "获取失败"), set, commentBack(params));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comment_up", counts.up());
        data.put("comment_down", counts.down());
        Map<String, Object> body = SiteRequests.reply(1, "操作成功");
        body.put("data", data);
        return answer(request, ajax, post, body, set, commentBack(params));
    }

    // ------------------------------------------------------------------ 页面

    /**
     * 评论页与片段：列表按旧 {@code {maccms:comment paging="yes"}}（Comment.php:131-162：{@code mid}、{@code rid}、{@code pid}、
     * {@code by}、{@code order}、{@code page} 来自 {@code mac_param_url}），条数取旧配置 {@code comment.pagesize}。
     */
    private ResponseEntity<?> commentPage(CommentService service, BoardSettings board, Map<String, PhpInput.Value> params,
                                          boolean fragment) {
        LegacyParams lp = LegacyParams.of(List.of(), SiteRequests.request(params));
        int mid = (int) Math.max(0, Math.min(99, lp.intval("mid")));
        long rid = Math.max(0, lp.intval("rid"));
        long pid = Math.max(0, lp.intval("pid"));
        int page = (int) Math.max(1, Math.min(MAX_PAGE, lp.intval("page")));
        BoardPage<CommentItem> p = service.comments(rid, mid, pid, 0, lp.text("by").orElse(""),
                lp.text("order").orElse(""), page, board.pageSize());
        Map<String, String> base = new LinkedHashMap<>();
        base.put("mid", Long.toString(mid));
        base.put("rid", Long.toString(rid));
        base.put("pid", Long.toString(pid));
        BoardList<CommentEntry> list = new BoardList<>(p.items().stream().map(this::entry).toList(), p.total(),
                "还没有评论。", pagination(fragment ? "comment/ajax" : "comment/index", base, p));
        Optional<BoardPostForm> form = Optional.empty();
        if (service.writable()) {
            List<HiddenField> fields = List.of(new HiddenField("comment_mid", Long.toString(mid)),
                    new HiddenField("comment_rid", Long.toString(rid)), new HiddenField("comment_pid", "0"));
            form = Optional.of(postForm("comment-post", "comment/savedata", fields, "comment_content", "发表评论",
                    board));
        }
        CommentPage view = new CommentPage(pages.member("评论"), "评论", target(mid, rid), list, form);
        return fragment ? renderer.memberPlain(HttpStatus.OK, t -> t.siteAjaxComment(view))
                : renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesComment(view));
    }

    private ResponseEntity<?> gbookPage(CommentService service, BoardSettings board, Map<String, PhpInput.Value> params,
                                        boolean fragment) {
        LegacyParams lp = LegacyParams.of(List.of(), SiteRequests.request(params));
        long rid = Math.max(0, lp.intval("rid"));
        int page = (int) Math.max(1, Math.min(MAX_PAGE, lp.intval("page")));
        BoardPage<GbookItem> p = service.gbooks(rid, 0, lp.text("by").orElse(""), lp.text("order").orElse(""), page,
                board.pageSize());
        Map<String, String> base = new LinkedHashMap<>();
        base.put("rid", Long.toString(rid));
        BoardList<GbookEntry> list = new BoardList<>(p.items().stream().map(SiteInteractionController::entry).toList(),
                p.total(), "还没有留言。", pagination(fragment ? "gbook/ajax" : "gbook/index", base, p));
        Optional<BoardPostForm> form = service.writable()
                ? Optional.of(postForm("gbook-post", "gbook/savedata", List.of(), "gbook_content", "我要留言", board))
                : Optional.empty();
        GbookPage view = new GbookPage(pages.member("留言本"), "留言本", "", list, form);
        return fragment ? renderer.memberPlain(HttpStatus.OK, t -> t.siteAjaxGbook(view))
                : renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesGbook(view));
    }

    /** 旧 {@code gbook/report}（Gbook.php:21-27）：报错表单，{@code gbook_rid} 为 {@code id}，正文预填 {@code name}。 */
    private ResponseEntity<?> reportPage(CommentService service, BoardSettings board, Map<String, PhpInput.Value> params) {
        LegacyParams lp = LegacyParams.of(List.of(), SiteRequests.request(params));
        String id = Long.toString(Math.max(0, lp.intval("id")));
        String name = lp.text("name").map(SiteInteractionController::unescape).orElse("");
        Optional<BoardPostForm> form = service.writable()
                ? Optional.of(postForm("gbook-report", "gbook/savedata", List.of(new HiddenField("gbook_rid", id)),
                        "gbook_content", "报错", board))
                : Optional.empty();
        GbookPage view = new GbookPage(pages.member("报错"), "数据报错", name, new BoardList<>(List.of(), 0, "",
                Optional.empty()), form);
        return renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesGbookReport(view));
    }

    private BoardPostForm postForm(String id, String action, List<HiddenField> fields, String contentName, String label,
                                   BoardSettings board) {
        Optional<MemberCaptcha> captcha = board.verify() ? Optional.of(new MemberCaptcha(site.publicPath(VERIFY_PATH)))
                : Optional.empty();
        Optional<SafeUrl> login = board.login() ? Optional.of(site.publicPath(urls.user("login"))) : Optional.empty();
        return new BoardPostForm(id, site.publicPath(urls.action(action, Map.of())), fields, contentName, label,
                captcha, login);
    }

    private CommentEntry entry(CommentItem c) {
        String id = Long.toString(c.id());
        SafeUrl digg = site.publicPath(urls.action("comment/digg", Map.of()));
        InlineForm up = new InlineForm(digg, List.of(new HiddenField("id", id), new HiddenField("type", "up")), "顶");
        InlineForm down = new InlineForm(digg, List.of(new HiddenField("id", id), new HiddenField("type", "down")), "踩");
        InlineForm report = new InlineForm(site.publicPath(urls.action("comment/report", Map.of())),
                List.of(new HiddenField("id", id)), "举报");
        return new CommentEntry(c.id(), c.name(), time(c.time()), c.content(), c.up(), c.down(), up, down, report,
                c.replies().stream().map(this::entry).toList());
    }

    private static GbookEntry entry(GbookItem g) {
        return new GbookEntry(g.id(), g.name(), time(g.time()), g.content(), g.reply(),
                g.replyTime() > 0 ? time(g.replyTime()) : "");
    }

    private <T> Optional<PaginationView> pagination(String model, Map<String, String> base, BoardPage<T> p) {
        if (p.pageCount() <= 1) {
            return Optional.empty();
        }
        int count = (int) Math.min(p.pageCount(), MAX_PAGE);
        Optional<SafeUrl> prev = p.page() > 1 ? Optional.of(pageUrl(model, base, p.page() - 1)) : Optional.empty();
        Optional<SafeUrl> next = p.page() < count ? Optional.of(pageUrl(model, base, p.page() + 1)) : Optional.empty();
        return Optional.of(new PaginationView(Math.min(p.page(), count), count, prev, next));
    }

    private SafeUrl pageUrl(String model, Map<String, String> base, int page) {
        Map<String, String> vars = new LinkedHashMap<>(base);
        vars.put("page", Integer.toString(page));
        return site.publicPath(urls.action(model, vars));
    }

    /** 被评论的内容页（旧 comment_mid：1 视频、2 文章、3 专题、8 演员、9 角色、11 网址）。 */
    private Optional<SafeUrl> target(int mid, long rid) {
        if (rid <= 0) {
            return Optional.empty();
        }
        try {
            String path = switch (mid) {
                case 1 -> urls.vodDetail(rid);
                case 2 -> urls.legacy(com.maccms.backend.site.application.SiteRoute.Legacy.of(
                        com.maccms.backend.site.application.LegacyPage.ART_DETAIL, rid));
                case 3 -> urls.legacy(com.maccms.backend.site.application.SiteRoute.Legacy.of(
                        com.maccms.backend.site.application.LegacyPage.TOPIC_DETAIL, rid));
                case 8 -> urls.legacy(com.maccms.backend.site.application.SiteRoute.Legacy.of(
                        com.maccms.backend.site.application.LegacyPage.ACTOR_DETAIL, rid));
                case 9 -> urls.legacy(com.maccms.backend.site.application.SiteRoute.Legacy.of(
                        com.maccms.backend.site.application.LegacyPage.ROLE_DETAIL, rid));
                case 11 -> urls.legacy(com.maccms.backend.site.application.SiteRoute.Legacy.of(
                        com.maccms.backend.site.application.LegacyPage.WEBSITE_DETAIL, rid));
                default -> null;
            };
            return path == null ? Optional.empty() : Optional.of(site.publicPath(path));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 非 ajax 提交后的"继续"：回到评论页（按提交的 comment_mid/comment_rid）。 */
    private Optional<SafeUrl> commentBack(Map<String, PhpInput.Value> params) {
        long mid = intval(params, "comment_mid");
        long rid = intval(params, "comment_rid");
        Map<String, String> vars = new LinkedHashMap<>();
        if (mid > 0 && mid < 100 && rid > 0) {
            vars.put("mid", Long.toString(mid));
            vars.put("rid", Long.toString(rid));
        }
        return Optional.of(site.publicPath(urls.action("comment/index", vars)));
    }

    // ------------------------------------------------------------------ 响应

    private ResponseEntity<?> answer(HttpServletRequest request, boolean ajax, boolean post, Map<String, Object> body,
                                     List<String> cookies, Optional<SafeUrl> back) {
        if (ajax || !post) {
            return withLabelUser(request, SiteRequests.json(body, cookies));
        }
        return jump(request, ((Integer) body.get("code")) == 1, (String) body.get("msg"), back, cookies);
    }

    private ResponseEntity<?> jump(HttpServletRequest request, boolean ok, String msg, Optional<SafeUrl> back,
                                   List<String> cookies) {
        UserJumpPage page = new UserJumpPage(pages.member(ok ? "操作成功" : "操作失败"), ok, SiteVariants.text(msg), back);
        ResponseEntity<String> r = renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesUserJump(page));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders());
        for (String c : cookies) {
            b.header(org.springframework.http.HttpHeaders.SET_COOKIE, c);
        }
        return withLabelUser(request, b.body(r.getBody()));
    }

    /** 旧版先由 {@code label_user} 写游客回写 cookie，再写本操作的 cookie（同 SiteUserController）。 */
    private static ResponseEntity<?> withLabelUser(HttpServletRequest request, ResponseEntity<?> r) {
        return SiteUserController.withLabelUserCookies(r, SiteMemberInterceptor.member(request).setCookies());
    }

    private ResponseEntity<?> unavailable(boolean ajax) {
        if (ajax) {
            return SiteRequests.unavailableJson("评论留言功能暂未开放");
        }
        return renderer.error(HttpStatus.SERVICE_UNAVAILABLE, pages.unavailable(), java.time.Duration.ofSeconds(5));
    }

    /**
     * ThinkPHP {@code cookie($name, 't', $timespan)}：有效期为旧配置 {@code timespan} 秒（非数字或为空时为会话 cookie），{@code path=/}。
     */
    static String timespanCookie(String name, BoardSettings b, long now) {
        Long life = b.cookieLifetime();
        return PhpCookies.setCookie(name, "t", life == null ? 0 : Math.max(1, now + life), now, "/");
    }

    // ------------------------------------------------------------------ 会员与参数

    /** 发表者：会员登录态（label_user 判定）通过时为会员，显示名为库里昵称（非空且不是 "0"）或用户名。 */
    private Poster poster(HttpServletRequest request) {
        MemberState s = SiteMemberInterceptor.member(request).state();
        if (!s.member()) {
            return Poster.GUEST;
        }
        String name = s.userName();
        MemberAccountService account = accounts.getIfAvailable();
        if (account != null) {
            MemberProfile p = account.profile(s);
            if (p != null && !p.nickName().isEmpty() && !p.nickName().equals("0")) {
                name = p.nickName();
            }
        }
        return new Poster(s.userId(), name);
    }

    /** 旧 {@code mac_get_ip_long}（application/common.php:990-999）：点分十进制 IPv4 才有值，否则为 0。 */
    static long legacyIp(String ip) {
        if (ip == null) {
            return 0;
        }
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return 0;
        }
        long v = 0;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || (part.length() > 1 && part.charAt(0) == '0')) {
                return 0;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return 0;
            }
            v = (v << 8) | octet;
        }
        return v >= 0xFFFF_FFFFL ? 0 : v;
    }

    private static long intval(Map<String, PhpInput.Value> params, String key) {
        byte[] v = PhpInput.scalar(params, key);
        return v == null ? 0 : com.maccms.backend.content.application.PhpIntval.of(
                new String(v, StandardCharsets.ISO_8859_1));
    }

    private static String time(long epoch) {
        return TIME.format(Instant.ofEpochSecond(epoch).atZone(PRC));
    }

    /** {@code mac_param_url} 的 htmlspecialchars 只为旧模板拼字符串；预填进表单时还原这四个实体（模板会再转义）。 */
    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    // ------------------------------------------------------------------ 地址

    private Route route(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        String pathInfo;
        if (raw.startsWith("/index.php/")) {
            pathInfo = raw.substring("/index.php/".length());
        } else if (urls.style().rewrite()) {
            pathInfo = raw.substring(1);
        } else {
            throw notFound();
        }
        if (pathInfo.length() > 1024) {
            throw notFound();
        }
        String decoded = SiteAjaxController.percentDecode(pathInfo).orElseThrow(SiteInteractionController::notFound);
        String p = trimSlashes(SUFFIX.matcher(decoded).replaceFirst(""));
        String[] seg = p.split("/", -1);
        Matcher alias = GBOOK_ALIAS.matcher(seg[0]);
        if (seg.length == 1 && urls.style().routeAliases() && alias.matches() && !seg[0].equals("gbook")) {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("page", alias.group(1));
            return new Route("gbook", "index", vars);
        }
        String controller = seg[0].toLowerCase(java.util.Locale.ROOT);
        if (!controller.equals("comment") && !controller.equals("gbook")) {
            throw notFound();
        }
        String action = seg.length > 1 && !seg[1].isEmpty() ? seg[1].toLowerCase(java.util.Locale.ROOT) : "index";
        List<String> rest = seg.length > 2 ? List.of(seg).subList(2, seg.length) : List.of();
        return new Route(controller, action, SiteRequests.pathVars(rest));
    }

    private record Route(String controller, String action, Map<String, String> pathVars) {
    }

    private static String trimSlashes(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == '/') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == '/') {
            b--;
        }
        return s.substring(a, b);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
    }
}

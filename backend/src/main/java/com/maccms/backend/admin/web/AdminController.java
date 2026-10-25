package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminAccountService;
import com.maccms.backend.admin.application.AdminAuthorization;
import com.maccms.backend.admin.application.AdminLoginService;
import com.maccms.backend.admin.application.AdminPermissions;
import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.application.view.AdminPrincipal;
import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.admin.config.ConditionalOnAdminEnabled;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 后台入口（B12，docs/architecture/21-b12-admin-framework.md 第 4 节）：{@code maccms.admin.entry} 之下的全部地址由这里按旧
 * {@code _admin.php} + ThinkPHP 路由 + {@code admin/controller/Base} 构造函数的顺序处理：
 * <ol>
 * <li>模块不是 {@code admin} → 302 到 {@code admin/index/index}（旧 Begin 行为）；控制器不存在 → 404（旧版在登录检查之前）；</li>
 * <li>除 {@code index/login}（与新版的登录页验证码 {@code index/verify}）外都要登录：会话里没有管理员，或 D2 ⑤ 重读当前行发现已停用、
 * 已改密、已删除 → 作废会话、302 到登录页；</li>
 * <li>除 {@code update} 控制器外按 {@code admin_auth} 判定权限（{@link AdminAuthorization}），不通过 → "您没有权限访问此页面"；</li>
 * <li>B12 的动作在这里实现；auth.php 中其余动作返回"由后续批次实现"的提示（{@link AdminPermissions#batchOf}）；不存在的动作 404。</li>
 * </ol>
 * POST 请求带 {@code Origin}（没有时看 {@code Referer}）且与 {@code maccms.admin.base-url} 不同源时 403。
 */
@Controller
@ConditionalOnAdminEnabled
public class AdminController {

    static final int MAX_FORM_BYTES = 256 * 1024;
    private static final String PENDING_TITLE = "跳转提示";

    private final AdminEndpoint endpoint;
    private final AdminSessions sessions;
    private final AdminSettings settings;
    private final AdminLoginService logins;
    private final AdminAccountService accounts;
    private final AdminPages pages;
    private final AdminResponses responses;
    private final ClientIpResolver clientIp;
    private final ContentCacheMaintenance contentCache;
    private final RandomGenerator random;

    public AdminController(AdminEndpoint endpoint, AdminSessions sessions, AdminSettings settings, AdminLoginService logins,
                           AdminAccountService accounts, AdminPages pages, AdminResponses responses,
                           ClientIpResolver clientIp, ContentCacheMaintenance contentCache, AdminRandom random) {
        this.endpoint = endpoint;
        this.sessions = sessions;
        this.settings = settings;
        this.logins = logins;
        this.accounts = accounts;
        this.pages = pages;
        this.responses = responses;
        this.clientIp = clientIp;
        this.contentCache = contentCache;
        this.random = random.generator();
    }

    /** 一次请求的上下文。 */
    record Ctx(HttpServletRequest request, HttpServletResponse response, AdminRoute route, AdminParams get,
               AdminParams post, AdminParams params, boolean post_, boolean ajax, LegacyAdminUrl url) {
    }

    @RequestMapping(value = {"${maccms.admin.entry}", "${maccms.admin.entry}/**"},
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<?> handle(HttpServletRequest request, HttpServletResponse response) {
        String pathInfo = pathInfo(request);
        if (pathInfo == null) {
            return notFound(false);
        }
        AdminRoute route = AdminRoute.parse(pathInfo);
        if (route.redirectHome()) {
            return responses.redirect(endpoint.root() + "/admin/index/index.html");
        }
        boolean isPost = "POST".equalsIgnoreCase(request.getMethod());
        AdminParams get = AdminParams.parseQuery(request.getQueryString());
        AdminParams post = isPost ? AdminParams.parse(body(request)) : AdminParams.empty();
        AdminParams params = AdminParams.merge(get, post, route.vars());
        boolean ajax = isAjax(request, params);
        if (!AdminPermissions.knownController(route.controller())) {
            return notFound(ajax);
        }
        if (!sourceExempt(isPost, route.code()) && !sameOrigin(request)) {
            // 不同源的请求（跨站请求伪造，D-B12-3）：SameSite=Strict 只挡别的站点，同站点的其他子域名或端口仍会带上会话 cookie，
            // 旧后台的删除、改状态、清缓存又都是 GET，所以除登录页与框架页外一律要求同源
            return responses.notice(ajax, HttpStatus.FORBIDDEN, PENDING_TITLE, "非法请求");
        }
        Ctx ctx = new Ctx(request, response, route, get, post, params, isPost, ajax,
                new LegacyAdminUrl(endpoint.root(), route.controllerName(), route.action()));
        String code = route.code();
        if (code.equals("index/login")) {
            return login(ctx);
        }
        if (code.equals("index/verify")) {
            return verify(ctx);
        }

        SessionState session = sessions.current(request);
        Optional<AdminPrincipal> principal = principal(session);
        if (principal.isEmpty()) {
            if (session != null && session.get(AdminSessions.ADMIN_ID, Long.class).isPresent()) {
                // D2 ⑤：已登录的会话，管理员被停用、改密或删除 → 作废
                sessions.invalidate(request, response);
            }
            return responses.redirect(endpoint.root() + "/admin/index/login.html");
        }
        AdminPrincipal admin = principal.get();
        if (!route.controller().equals("update")
                && !AdminAuthorization.allowed(admin.adminId(), admin.adminAuth(), route.controller(), route.action())) {
            return responses.error(ajax, settings.lang().get("permission_denied"));
        }
        return switch (code) {
            case "index/index" -> pages.frame(ctx, admin);
            case "index/welcome" -> pages.welcome(ctx, admin);
            case "index/quickmenu" -> isPost
                    ? pending(ctx, "B15")
                    : pages.quickmenu(ctx, token(session));
            case "index/checkcache" -> responses.text("no");
            case "index/clear" -> clear(ctx);
            case "index/iframe" -> iframe(ctx);
            case "index/unlocked" -> unlocked(ctx, session);
            case "index/logout" -> logout(ctx);
            case "admin/index" -> pages.adminList(ctx, admin, accounts.list(ctx.params().scalar("wd"),
                    ctx.params().scalar("page"), ctx.params().scalar("limit"), settings.pageSize()));
            case "admin/info" -> isPost ? saveAdmin(ctx, session) : pages.adminInfo(ctx,
                    accounts.form(ctx.params().scalar("id")).orElse(null), token(session));
            case "admin/del" -> reply(ctx, accounts.delete(ids(ctx.params()), admin.adminId()));
            case "admin/field" -> reply(ctx, accounts.setStatus(ids(ctx.params()), ctx.params().scalar("col"),
                    ctx.params().scalar("val")));
            default -> {
                String batch = AdminPermissions.batchOf(route.controller(), route.action());
                yield batch == null ? notFound(ajax) : pending(ctx, batch);
            }
        };
    }

    // ------------------------------------------------------------------ 登录、验证码、退出

    /** 旧 Index::login（admin/controller/Index.php:12-23）。 */
    private ResponseEntity<String> login(Ctx ctx) {
        if (!ctx.post_()) {
            return pages.login(ctx);
        }
        byte[] name = ctx.post().scalar("admin_name");
        byte[] pwd = ctx.post().scalar("admin_pwd");
        byte[] verify = ctx.post().scalar("verify");
        SessionState before = sessions.current(ctx.request());
        AdminLoginService.LoginResult r = logins.login(name, pwd, settings.loginVerify(),
                () -> Captchas.check(before, verify == null ? null : new String(verify, StandardCharsets.UTF_8),
                        Captchas.DEFAULT_SLOT, sessions.nowEpochSeconds()),
                AdminLoginService.ipLong(clientIp.resolve(ctx.request())));
        if (!r.ok()) {
            return responses.error(ctx.ajax(), settings.lang().get(r.failure().langKey()));
        }
        SessionState s = sessions.rotate(ctx.request(), ctx.response());
        s.put(AdminSessions.ADMIN_ID, r.adminId());
        s.put(AdminSessions.ADMIN_PWD, r.pwd());
        return responses.success(ctx.ajax(), settings.lang().get("model/admin/login_ok"), successUrl(ctx));
    }

    /** 登录页的验证码图片（新版放在后台入口下，D-B12-4）：同前台 {@code verify/index}，记录写进后台会话。 */
    private ResponseEntity<byte[]> verify(Ctx ctx) {
        SessionState session = sessions.currentOrCreate(ctx.request(), ctx.response());
        byte[] png = Captchas.issue(session, Captchas.DEFAULT_SLOT, settings.captcha(), sessions.nowEpochSeconds(), random);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(png.length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(png);
    }

    /** 旧 Index::logout（Index.php:25-29）：清会话，302 到登录页。 */
    private ResponseEntity<String> logout(Ctx ctx) {
        sessions.invalidate(ctx.request(), ctx.response());
        return responses.redirect(endpoint.root() + "/admin/index/login.html");
    }

    /** 旧 Index::unlocked（Index.php:180-190）：{@code input()} 的 password。 */
    private ResponseEntity<String> unlocked(Ctx ctx, SessionState session) {
        String pwd = session.get(AdminSessions.ADMIN_PWD, String.class).orElse(null);
        if (!logins.unlock(pwd, ctx.params().scalar("password"))) {
            return responses.error(ctx.ajax(), settings.lang().get("admin/index/pass_err"));
        }
        return responses.success(ctx.ajax(), settings.lang().get("admin/index/unlock_ok"), successUrl(ctx));
    }

    // ------------------------------------------------------------------ 框架页的操作

    /**
     * 旧 Index::clear（Index.php:157-168）：旧版清运行目录（cache/log/temp）与缓存驱动、按播放器配置重写 playerconfig.js，再强制清理
     * 过期的视频搜索缓存。新版没有运行目录与缓存驱动，playerconfig.js 由 B05 按旧配置即时生成，只剩视频搜索缓存（21 文档第 4.6 节）。
     */
    private ResponseEntity<String> clear(Ctx ctx) {
        contentCache.clearVodSearchCache();
        return responses.success(ctx.ajax(), settings.lang().get("admin/index/clear_ok"), successUrl(ctx));
    }

    /** 旧 Index::iframe（Index.php:170-182）：{@code input('post.val', 0)}，1 写 {@code is_iframe=yes}，0 删除，其他值报错（旧提示照旧）。 */
    private ResponseEntity<String> iframe(Ctx ctx) {
        byte[] raw = ctx.post().scalar("val");
        String v = raw == null ? "0" : new String(raw, StandardCharsets.ISO_8859_1);
        // PHP 8：数字字符串与 0、1 按数值比较，非数字字符串（含空串）两者都不等
        boolean one = LegacyInput.looseEqualsNumber(v, 1);
        boolean zero = raw == null || LegacyInput.looseEqualsNumber(v, 0);
        if (!one && !zero) {
            return responses.error(ctx.ajax(), settings.lang().get("admin/index/clear_ok"));
        }
        if (one) {
            ctx.response().addHeader(HttpHeaders.SET_COOKIE, "is_iframe=yes; path=/");
        } else {
            ctx.response().addHeader(HttpHeaders.SET_COOKIE,
                    "is_iframe=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/");
        }
        return responses.success(ctx.ajax(), settings.lang().get("admin/index/iframe"), successUrl(ctx));
    }

    // ------------------------------------------------------------------ 管理员

    /**
     * 旧 Admin::info 的 POST（admin/controller/Admin.php:42-55）：先补 {@code index/welcome}，再校验表单令牌（缺失 → 非法请求；不一致 →
     * 请不要重复提交表单，令牌随之作废），最后保存。
     */
    private ResponseEntity<String> saveAdmin(Ctx ctx, SessionState session) {
        AdminParams p = ctx.post();
        if (p.has("admin_auth") && p.list("admin_auth") == null) {
            // 标量的 admin_auth：旧版对字符串执行 $x[] = …（PHP 7/8 都是致命错误）
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        List<byte[]> auth = new ArrayList<>(p.list("admin_auth") == null ? List.of() : p.list("admin_auth"));
        boolean hasWelcome = auth.stream().anyMatch(b -> Arrays.equals(b, "index/welcome".getBytes(StandardCharsets.US_ASCII)));
        if (!hasWelcome) {
            auth.add("index/welcome".getBytes(StandardCharsets.US_ASCII));
        }
        byte[] posted = p.scalar("__token__");
        if (posted == null || posted.length == 0) {
            return responses.error(ctx.ajax(), settings.lang().get("illegal_request"));
        }
        String expected = session.take(AdminSessions.TOKEN, String.class).orElse(null);
        if (expected == null || !expected.equals(new String(posted, StandardCharsets.ISO_8859_1))) {
            return responses.error(ctx.ajax(), settings.lang().get("token_err"));
        }
        Reply r = accounts.save(p.scalar("admin_id"), p.scalar("admin_name"), p.scalar("admin_pwd"),
                p.scalar("admin_status"), auth);
        return reply(ctx, r);
    }

    private ResponseEntity<String> reply(Ctx ctx, Reply r) {
        return r.ok() ? responses.success(ctx.ajax(), r.message(), successUrl(ctx))
                : responses.error(ctx.ajax(), r.message());
    }

    /** 旧版 {@code $param['ids']}：数组时取各项，字符串时按逗号拆分；缺失为 null。 */
    static List<byte[]> ids(AdminParams params) {
        List<byte[]> list = params.list("ids");
        if (list != null) {
            return list;
        }
        byte[] s = params.scalar("ids");
        if (s == null) {
            return null;
        }
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= s.length; i++) {
            if (i == s.length || s[i] == ',') {
                out.add(Arrays.copyOfRange(s, start, i));
                start = i + 1;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 公共

    private ResponseEntity<String> pending(Ctx ctx, String batch) {
        return responses.notice(ctx.ajax(), HttpStatus.NOT_IMPLEMENTED, PENDING_TITLE,
                "该功能尚未在新系统中实现（由 " + batch + " 批次提供）");
    }

    private ResponseEntity<String> notFound(boolean ajax) {
        return responses.notice(ajax, HttpStatus.NOT_FOUND, PENDING_TITLE, "页面不存在");
    }

    /** 新表单令牌（旧 {@code {$Request.token}}）：每次打开表单都覆盖会话里的令牌。 */
    private String token(SessionState session) {
        byte[] b = new byte[16];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        session.put(AdminSessions.TOKEN, token);
        return token;
    }

    private Optional<AdminPrincipal> principal(SessionState session) {
        if (session == null) {
            return Optional.empty();
        }
        Optional<Long> id = session.get(AdminSessions.ADMIN_ID, Long.class);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return logins.current(id.get(), session.get(AdminSessions.ADMIN_PWD, String.class).orElse(null));
    }

    /**
     * 旧 {@code success()} 的跳转地址：有 Referer 时用它（只接受 http/https 绝对地址或以 / 开头的站内路径），否则为当前动作的
     * {@code url()}（Jump.php:32-36）。
     */
    private String successUrl(Ctx ctx) {
        String ref = ctx.request().getHeader(HttpHeaders.REFERER);
        if (ref != null) {
            String lower = ref.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://") || (ref.startsWith("/") && !ref.startsWith("//"))) {
                return ref;
            }
        }
        return ctx.url().build("");
    }

    /** ThinkPHP {@code isAjax()}：{@code X-Requested-With: XMLHttpRequest}（不分大小写），或参数 {@code _ajax} 为 PHP 真值。 */
    static boolean isAjax(HttpServletRequest request, AdminParams params) {
        String h = request.getHeader("X-Requested-With");
        if (h != null && h.equalsIgnoreCase("xmlhttprequest")) {
            return true;
        }
        byte[] v = params.scalar("_ajax");
        if (v == null) {
            return params.list("_ajax") != null && !params.list("_ajax").isEmpty();
        }
        return !(v.length == 0 || (v.length == 1 && v[0] == '0'));
    }

    /** 不校验来源的只读入口：GET/HEAD 的登录页与框架页，可以从书签或别的网站直接打开。 */
    static boolean sourceExempt(boolean isPost, String code) {
        return !isPost && (code.equals("index/login") || code.equals("index/index"));
    }

    /**
     * 请求是否来自后台自己的页面：浏览器给了 {@code Sec-Fetch-Site} 时只认 {@code same-origin} 与 {@code none}（地址栏、书签）；
     * 再看 {@code Origin}，没有时看 {@code Referer} 的源；都没有（非浏览器客户端）时放行。
     */
    private boolean sameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equalsIgnoreCase("same-origin") && !site.equalsIgnoreCase("none")) {
            return false;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            String ref = request.getHeader(HttpHeaders.REFERER);
            if (ref == null) {
                return true;
            }
            int slash = ref.indexOf('/', ref.indexOf("//") + 2);
            origin = slash < 0 ? ref : ref.substring(0, slash);
        }
        return origin.toLowerCase(Locale.ROOT).equals(endpoint.origin());
    }

    /** 入口之后的路径（按 UTF-8 解码一次）；不在入口下或不是合法 UTF-8 时为 null。 */
    private String pathInfo(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctxPath = request.getContextPath();
        if (ctxPath != null && !ctxPath.isEmpty() && uri.startsWith(ctxPath)) {
            uri = uri.substring(ctxPath.length());
        }
        if (!uri.startsWith(endpoint.entry())) {
            return null;
        }
        String rest = uri.substring(endpoint.entry().length());
        if (!rest.isEmpty() && !rest.startsWith("/")) {
            return null;
        }
        byte[] decoded = PhpCookies.rawUrlDecode(rest);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static byte[] body(HttpServletRequest request) {
        String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/x-www-form-urlencoded")) {
            return new byte[0];
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_FORM_BYTES + 1);
            if (bytes.length > MAX_FORM_BYTES) {
                throw new ApiException(ErrorCode.INVALID_PARAMETER, "请求正文过大");
            }
            return bytes;
        } catch (IOException e) {
            return new byte[0];
        }
    }
}

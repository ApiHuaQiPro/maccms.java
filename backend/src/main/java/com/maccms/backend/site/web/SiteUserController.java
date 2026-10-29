package com.maccms.backend.site.web;

import com.maccms.backend.comment.application.CommentService;
import com.maccms.backend.common.browser.BrowserExternalAccess;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberPaymentService;
import com.maccms.backend.member.application.MemberCookies;
import com.maccms.backend.member.application.MemberPurchaseService;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.application.view.PortraitReply;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.PlayerFramePolicy;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.site.web.view.MemberCaptcha;
import com.maccms.backend.site.web.view.MemberShell;
import com.maccms.backend.site.web.view.MetadataItem;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.UserBindPage;
import com.maccms.backend.site.web.view.UserFindpassMsgPage;
import com.maccms.backend.site.web.view.UserFindpassPage;
import com.maccms.backend.site.web.view.UserIndexPage;
import com.maccms.backend.site.web.view.UserInfoFragment;
import com.maccms.backend.site.web.view.UserInfoPage;
import com.maccms.backend.site.web.view.UserJumpPage;
import com.maccms.backend.site.web.view.UserLoginForm;
import com.maccms.backend.site.web.view.UserLoginFragment;
import com.maccms.backend.site.web.view.UserLoginPage;
import com.maccms.backend.site.web.view.UserMessageBlock;
import com.maccms.backend.site.web.view.UserPortraitPage;
import com.maccms.backend.site.web.view.UserRegPage;
import com.maccms.backend.site.web.view.UserUnbindPage;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * 旧前台会员控制器 {@code index/controller/User.php} 的 B09 部分（docs/architecture/18-b09-member-account.md 第 2、3 节）：登录、
 * 注销、注册、资料、头像、密保找回，以及 B25 前按"未配置"返回的短信/邮件入口。业务规则在 {@link MemberAccountService}；这里负责地址
 * 解析、按字节读取参数、验证码会话、页面与 JSON 输出、cookie。
 *
 * <p>地址：{@code /index.php/user/<操作>[.html][/键/值…]}（{@code rewrite} 打开时另认去掉 {@code /index.php} 的形态）。B10 的会员权益与
 * 财务入口在登录态检查之后交给 {@link SiteMemberFinance}（19 文档），B11 的"我的评论/留言"交给 {@link SiteMemberBoards}（20 文档）。
 * 未实现的操作（B25/B26）返回 404；但与旧版一样，先按旧控制器构造函数的
 * 白名单检查登录态（{@code User.php:19-25}），未登录时先给"未登录"。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteUserController {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(?:html|htm|shtm|shtml|xml)$");
    private static final Pattern PATH_PAIR = Pattern.compile("(\\w+)\\|([^|]+)");
    /** 旧构造函数中不检查登录态的操作（{@code User.php:19}）。 */
    private static final Set<String> PUBLIC_ACTIONS = Set.of("login", "logout", "ajax_login", "reg", "findpass",
            "findpass_msg", "findpass_reset", "reg_msg", "oauth", "logincallback", "visit");
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final String SCRIPT_PATH = "site/member.js";
    private static final String VERIFY_PATH = "index.php/verify/index.html";

    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final SiteHtmlRenderer renderer;
    private final SitePages pages;
    private final SiteSessions sessions;
    private final ClientIpResolver clientIp;
    private final ObjectProvider<MemberAccountService> accounts;
    /** B10 的入口（会员权益与财务）；没有对应服务（只装配了部分 Bean 的测试）时为 null，这些入口按 503。 */
    private final SiteMemberFinance finance;
    /** B11 的"我的评论/留言"；没有评论服务时为 null，这两个入口按 404。 */
    private SiteMemberBoards boards;

    public SiteUserController(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer, SiteSessions sessions,
                              ClientIpResolver clientIp, ObjectProvider<MemberAccountService> accounts) {
        this(pages, urls, renderer, sessions, clientIp, accounts, null);
    }

    @Autowired
    public SiteUserController(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer, SiteSessions sessions,
                              ClientIpResolver clientIp, ObjectProvider<MemberAccountService> accounts,
                              ObjectProvider<ImageUrlNormalizer> images, ObjectProvider<ContentLookupService> content,
                              ObjectProvider<MemberRecordService> records, ObjectProvider<MemberPurchaseService> purchases,
                              ObjectProvider<MemberWalletService> wallet, ObjectProvider<MemberReferralService> referral,
                              ObjectProvider<CommentService> comments, ObjectProvider<MemberPaymentService> payments,
                              BrowserExternalAccess external) {
        this(pages, urls, renderer, sessions, clientIp, accounts, finance(pages, urls, renderer, clientIp,
                images.getIfAvailable(), content.getIfAvailable(), records.getIfAvailable(),
                purchases.getIfAvailable(), wallet.getIfAvailable(), referral.getIfAvailable(),
                payments.getIfAvailable(), external.w3()));
        CommentService c = comments.getIfAvailable();
        this.boards = c == null ? null : new SiteMemberBoards(pages, urls, renderer, c);
    }

    SiteUserController(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer, SiteSessions sessions,
                       ClientIpResolver clientIp, ObjectProvider<MemberAccountService> accounts,
                       SiteMemberFinance finance) {
        this.site = pages.site();
        this.pages = pages;
        this.urls = urls;
        this.renderer = renderer;
        this.sessions = sessions;
        this.clientIp = clientIp;
        this.accounts = accounts;
        this.finance = finance;
    }

    private static SiteMemberFinance finance(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer,
                                             ClientIpResolver clientIp, ImageUrlNormalizer images,
                                             ContentLookupService content, MemberRecordService records,
                                             MemberPurchaseService purchases, MemberWalletService wallet,
                                             MemberReferralService referral, MemberPaymentService payments,
                                             boolean w3) {
        if (images == null || content == null || records == null || purchases == null || wallet == null
                || referral == null) {
            return null;
        }
        return new SiteMemberFinance(pages, urls, renderer, clientIp, images, content, records, purchases, wallet,
                referral, payments, w3);
    }

    @RequestMapping(value = {"/index.php/user", "/index.php/user/**", "/user", "/user/**"},
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<?> handle(HttpServletRequest request) {
        return withLabelUserCookies(dispatch(request), SiteMemberInterceptor.member(request).setCookies());
    }

    /**
     * 旧版先由 {@code label_user} 写游客回写/组 cookie，再由动作写自己的 cookie，浏览器按顺序收到全部 {@code Set-Cookie}。
     * Spring 写出 {@link ResponseEntity} 的 {@code Set-Cookie} 时会替换拦截器已经加上的同名头，所以这里把拦截器的 cookie
     * 按旧顺序放在前面。
     */
    static ResponseEntity<?> withLabelUserCookies(ResponseEntity<?> r, List<String> labelUser) {
        List<String> own = r.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (labelUser.isEmpty() || own == null || own.isEmpty()) {
            return r;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(r.getHeaders());
        List<String> all = new ArrayList<>(labelUser);
        all.addAll(own);
        headers.put(HttpHeaders.SET_COOKIE, all);
        return new ResponseEntity<>(r.getBody(), headers, r.getStatusCode());
    }

    private ResponseEntity<?> dispatch(HttpServletRequest request) {
        Route route = route(request);
        MemberAccountService account = accounts.getIfAvailable();
        boolean ajax = isAjax(request, null);
        if (account == null || !account.available()) {
            return unavailable(ajax);
        }
        Map<String, PhpInput.Value> params = params(request, route.pathVars());
        ajax = isAjax(request, params);
        PageMember member = SiteMemberInterceptor.member(request);
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        boolean b10 = SiteMemberFinance.ACTIONS.contains(route.action());
        if (b10 && (finance == null || !finance.available())) {
            return unavailable(ajax);
        }
        if (!PUBLIC_ACTIONS.contains(route.action()) && !member.state().member()) {
            return notLoggedIn(ajax);
        }
        if (b10) {
            return finance.handle(route.action(), request, params, member.state(), ajax, post);
        }
        return switch (route.action()) {
            case "login" -> post ? json(account.login(params, captcha(request, params), clientIp.resolve(request)))
                    : loginPage(request, account.pageSettings());
            case "logout" -> logout(request, account, ajax);
            case "ajax_login" -> renderer.memberPlain(HttpStatus.OK,
                    t -> t.siteAjaxUserLogin(new UserLoginFragment(loginForm(account.pageSettings()))));
            case "ajax_info" -> ajaxInfo(account, member.state());
            case "index" -> indexPage(account, member.state());
            case "reg" -> post ? json(account.register(member.state(), params, cookies(request), captcha(request, params),
                    clientIp.resolve(request))) : regPage(params, account.pageSettings());
            case "regcheck" -> regcheck(request, account, params);
            case "reg_msg" -> json(account.sendMessage(member.state(), params, 3));
            case "bindmsg" -> json(account.sendMessage(member.state(), params, 1));
            case "bind" -> post ? json(account.bind(member.state(), params)) : bindPage(params);
            case "unbind" -> post ? json(account.unbind(member.state(), params)) : unbindPage(params);
            case "info" -> post ? info(request, account, member.state(), params, ajax)
                    : infoPage(account, member.state());
            case "portrait" -> post ? portrait(request, account, member.state(), params)
                    : portraitPage(account, member.state());
            case "findpass" -> post ? json(account.findpass(params, captcha(request, params))) : findpassPage();
            case "findpass_msg" -> post ? json(account.sendMessage(member.state(), params, 2)) : findpassMsgPage(params);
            case "findpass_reset" -> post ? json(account.findpassReset(member.state(), params)) : emptyBody();
            case "comment", "gbook" -> {
                if (boards == null) {
                    throw notFound();
                }
                yield boards.handle(route.action(), member.state(), params);
            }
            default -> throw notFound();
        };
    }

    // ------------------------------------------------------------------ 动作

    private ResponseEntity<?> loginPage(HttpServletRequest request, MemberPageSettings s) {
        // 旧 User.php:187-189：只看 cookie 是否非空，不校验，直接转到会员中心（那里再判定登录态）
        Map<String, PhpCookies.Value> c = cookies(request);
        if (truthy(c.get("user_id")) && truthy(c.get("user_name"))) {
            return redirect(urls.user("index"));
        }
        MemberShell shell = shell("会员登录");
        UserLoginPage page = new UserLoginPage(shell, loginForm(s));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserLogin(page), shell.nonce());
    }

    private ResponseEntity<?> logout(HttpServletRequest request, MemberAccountService account, boolean ajax) {
        AccountReply reply = account.logout(cookies(request));
        if (ajax) {
            return json(reply);
        }
        return withCookies(ResponseEntity.status(HttpStatus.FOUND).location(java.net.URI.create(
                site.publicPath(urls.user("login")).href())), reply.setCookies())
                .cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<?> ajaxInfo(MemberAccountService account, MemberState member) {
        MemberProfile p = account.profile(member);
        if (p == null) {
            return notLoggedIn(false);
        }
        UserInfoFragment page = new UserInfoFragment(p, avatar(p), link("index"), link("logout"));
        return renderer.memberPlain(HttpStatus.OK, t -> t.siteAjaxUserInfo(page));
    }

    private ResponseEntity<?> indexPage(MemberAccountService account, MemberState member) {
        MemberProfile p = account.profile(member);
        if (p == null) {
            return notLoggedIn(false);
        }
        MemberShell shell = shell("会员中心");
        List<NavLink> financeLinks = List.of();
        List<MetadataItem> promotion = List.of();
        if (finance != null) {
            financeLinks = List.of(new NavLink("充值", link("buy"), false), new NavLink("升级会员", link("upgrade"), false),
                    new NavLink("我的权限", link("popedom"), false), new NavLink("我的收藏", link("favs"), false),
                    new NavLink("播放记录", link("plays"), false), new NavLink("下载记录", link("downs"), false),
                    new NavLink("全部记录", link("ulog"), false), new NavLink("积分日志", link("plog"), false),
                    new NavLink("积分提现", link("cash"), false), new NavLink("推广会员", link("reward"), false),
                    new NavLink("我的订单", link("orders"), false), new NavLink("充值卡记录", link("cards"), false));
            promotion = List.of(new MetadataItem("推广注册", finance.promotionUrl("reg", p.userId())),
                    new MetadataItem("推广访问", finance.promotionUrl("visit", p.userId())));
        }
        if (boards != null) {
            List<NavLink> links = new ArrayList<>(financeLinks);
            links.add(new NavLink("我的评论", link("comment"), false));
            links.add(new NavLink("我的留言", link("gbook"), false));
            financeLinks = links;
        }
        UserIndexPage page = new UserIndexPage(shell, p, avatar(p), link("info"), link("portrait"), link("logout"),
                financeLinks, promotion);
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserIndex(page), shell.nonce());
    }

    private ResponseEntity<?> regPage(Map<String, PhpInput.Value> params, MemberPageSettings s) {
        MemberShell shell = shell("会员注册");
        Optional<UserMessageBlock> message = Optional.empty();
        if (s.regPhoneSms()) {
            message = Optional.of(new UserMessageBlock("phone", "手机", link("reg_msg")));
        } else if (s.regEmailSms()) {
            message = Optional.of(new UserMessageBlock("email", "邮箱", link("reg_msg")));
        }
        UserRegPage page = new UserRegPage(shell, s.regOpen(), link("reg"), link("index"),
                s.regVerify() ? Optional.of(captchaView()) : Optional.empty(), message, link("login"));
        ResponseEntity<String> r = renderer.member(HttpStatus.OK, t -> t.sitePagesUserReg(page), shell.nonce());
        byte[] uid = PhpInput.scalar(params, "uid");
        if (uid != null && !isPhpEmpty(uid)) {
            // 旧 cookie('uid', $param['uid'])：会话 cookie，path=/
            return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                    .header(HttpHeaders.SET_COOKIE, PhpCookies.setCookie("uid",
                            new String(uid, StandardCharsets.UTF_8), 0, 0, MemberCookies.PATH))
                    .body(r.getBody());
        }
        return r;
    }

    private ResponseEntity<?> regcheck(HttpServletRequest request, MemberAccountService account,
                                       Map<String, PhpInput.Value> params) {
        String body = account.regcheck(params, code -> Captchas.check(sessions.current(request), code,
                Captchas.DEFAULT_SLOT, sessions.nowEpochSeconds()));
        if (body == null) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("code", 1);
            ok.put("msg", "ok");
            return jsonBody(ok, List.of());
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(HTML_UTF8).contentLength(bytes.length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex").body(bytes);
    }

    private ResponseEntity<?> info(HttpServletRequest request, MemberAccountService account, MemberState member,
                                   Map<String, PhpInput.Value> params, boolean ajax) {
        AccountReply reply = account.updateProfile(member, params);
        if (ajax) {
            // ThinkPHP success()/error()：成功时 url 为 Referer（没有则 null），失败时 ajax 下为空串
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", reply.ok() ? 1 : 0);
            body.put("msg", reply.msg());
            body.put("data", "");
            body.put("url", reply.ok() ? request.getHeader(HttpHeaders.REFERER) : "");
            body.put("wait", 3);
            return jsonBody(body, reply.setCookies());
        }
        UserJumpPage page = new UserJumpPage(pages.member(reply.ok() ? "操作成功" : "操作失败"), reply.ok(), SiteVariants.text(reply.msg()),
                Optional.of(link("info")));
        ResponseEntity<String> r = renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesUserJump(page));
        return withCookies(ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()), reply.setCookies())
                .body(r.getBody());
    }

    private ResponseEntity<?> infoPage(MemberAccountService account, MemberState member) {
        MemberProfile p = account.profile(member);
        if (p == null) {
            return notLoggedIn(false);
        }
        MemberShell shell = shell("修改资料");
        UserInfoPage page = new UserInfoPage(shell, p, link("info"), link("index"));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserInfo(page), shell.nonce());
    }

    private ResponseEntity<?> portrait(HttpServletRequest request, MemberAccountService account, MemberState member,
                                       Map<String, PhpInput.Value> params) {
        byte[] file = null;
        String fileName = null;
        String imgdata = null;
        try {
            if (request instanceof MultipartHttpServletRequest mr) {
                MultipartFile f = mr.getFile("file");
                if (f != null && !f.isEmpty()) {
                    file = f.getBytes();
                    fileName = f.getOriginalFilename();
                }
                imgdata = mr.getParameter("imgdata");
            } else {
                byte[] raw = PhpInput.scalar(params, "imgdata");
                imgdata = raw == null ? null : new String(raw, StandardCharsets.ISO_8859_1);
            }
        } catch (IOException | MultipartException e) {
            // 超过 2 MB / 3 MB 或正文无法解析：同旧版 PHP 丢弃上传（$_FILES 无 tmp_name），按"未找到上传的文件"
            file = null;
            imgdata = null;
        }
        PortraitReply reply = account.portrait(member, file, fileName, imgdata);
        Map<String, Object> body = new LinkedHashMap<>();
        if (reply.disabled()) {
            body.put("code", reply.code());
            body.put("msg", reply.msg());
        } else {
            body.put("msg", reply.msg());
            body.put("code", reply.code());
            body.put("file", reply.file());
        }
        return jsonBody(body, List.of());
    }

    private ResponseEntity<?> portraitPage(MemberAccountService account, MemberState member) {
        MemberProfile p = account.profile(member);
        if (p == null) {
            return notLoggedIn(false);
        }
        MemberShell shell = shell("修改头像");
        UserPortraitPage page = new UserPortraitPage(shell, avatar(p),
                account.pageSettings().portrait() ? Optional.of(link("portrait")) : Optional.empty(), link("index"));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserPortrait(page), shell.nonce());
    }

    private ResponseEntity<?> findpassPage() {
        MemberShell shell = shell("找回密码");
        UserFindpassPage page = new UserFindpassPage(shell, link("findpass"), link("login"), captchaView(),
                site.publicPath(urls.user("findpass_msg", Map.of("ac", "email"))),
                site.publicPath(urls.user("findpass_msg", Map.of("ac", "phone"))), link("login"));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserFindpass(page), shell.nonce());
    }

    private ResponseEntity<?> findpassMsgPage(Map<String, PhpInput.Value> params) {
        byte[] ac = PhpInput.scalar(params, "ac");
        boolean phone = ac != null && new String(ac, StandardCharsets.UTF_8).equals("phone");
        MemberShell shell = shell(phone ? "通过手机找回密码" : "通过邮箱找回密码");
        UserFindpassMsgPage page = new UserFindpassMsgPage(shell,
                new UserMessageBlock(phone ? "phone" : "email", phone ? "手机" : "邮箱", link("findpass_msg")),
                link("findpass_reset"), link("login"));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserFindpassMsg(page), shell.nonce());
    }

    /** 旧 {@code bind()} GET（{@code User.php:294-308}）：{@code ac} 为空时按 {@code email}。 */
    private ResponseEntity<?> bindPage(Map<String, PhpInput.Value> params) {
        byte[] ac = PhpInput.scalar(params, "ac");
        boolean phone = ac != null && new String(ac, StandardCharsets.UTF_8).equals("phone");
        MemberShell shell = shell(phone ? "绑定手机" : "绑定邮箱");
        UserBindPage page = new UserBindPage(shell,
                new UserMessageBlock(phone ? "phone" : "email", phone ? "手机" : "邮箱", link("bindmsg")), link("bind"),
                link("info"), site.publicPath(urls.user("bind", Map.of("ac", phone ? "email" : "phone"))),
                phone ? "改为绑定邮箱" : "改为绑定手机", link("info"));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserBind(page), shell.nonce());
    }

    /** 旧 {@code unbind()} GET（{@code User.php:310-319}）。 */
    private ResponseEntity<?> unbindPage(Map<String, PhpInput.Value> params) {
        byte[] ac = PhpInput.scalar(params, "ac");
        boolean phone = ac != null && new String(ac, StandardCharsets.UTF_8).equals("phone");
        MemberShell shell = shell(phone ? "解除绑定手机" : "解除绑定邮箱");
        UserUnbindPage page = new UserUnbindPage(shell, phone ? "phone" : "email", phone ? "手机" : "邮箱", link("unbind"),
                link("info"), link("info"));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserUnbind(page), shell.nonce());
    }

    // ------------------------------------------------------------------ 通用响应

    /** 旧构造函数（User.php:22-25）：先删 6 个会员 cookie，再 error('未登录', url('user/login'))。 */
    private ResponseEntity<?> notLoggedIn(boolean ajax) {
        String login = site.publicPath(urls.user("login")).href();
        if (ajax) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 0);
            body.put("msg", "未登录");
            body.put("data", "");
            body.put("url", login);
            body.put("wait", 3);
            return jsonBody(body, MemberCookies.logout());
        }
        UserJumpPage page = new UserJumpPage(pages.member("未登录"), false, SiteVariants.text("未登录"),
                Optional.of(link("login")));
        ResponseEntity<String> r = renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesUserJump(page));
        return withCookies(ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()), MemberCookies.logout())
                .body(r.getBody());
    }

    private ResponseEntity<?> unavailable(boolean ajax) {
        if (ajax) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 503);
            body.put("msg", SiteVariants.text("会员功能暂未开放"));
            byte[] bytes = LegacyJson.encode(body).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(JSON).contentLength(bytes.length)
                    .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(bytes);
        }
        return renderer.error(HttpStatus.SERVICE_UNAVAILABLE, pages.memberUnavailable(), null);
    }

    private ResponseEntity<byte[]> json(AccountReply reply) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", reply.code());
        body.put("msg", reply.msg());
        return jsonBody(body, reply.setCookies());
    }

    /** 旧版 {@code json($res)} 的响应（{@code body} 为有序 Map 或 List）；B10 的 {@link SiteMemberFinance} 共用。 */
    static ResponseEntity<byte[]> jsonBody(Object body, List<String> cookies) {
        byte[] bytes = LegacyJson.encode(SiteVariants.localizeMsg(body)).getBytes(StandardCharsets.UTF_8);
        return withCookies(ResponseEntity.ok(), cookies).contentType(JSON).contentLength(bytes.length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex").body(bytes);
    }

    private static ResponseEntity.BodyBuilder withCookies(ResponseEntity.BodyBuilder b, List<String> cookies) {
        for (String c : cookies) {
            b.header(HttpHeaders.SET_COOKIE, c);
        }
        return b;
    }

    private ResponseEntity<?> redirect(String sitePath) {
        return ResponseEntity.status(HttpStatus.FOUND).location(java.net.URI.create(site.publicPath(sitePath).href()))
                .cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<byte[]> emptyBody() {
        return ResponseEntity.ok().contentType(HTML_UTF8).contentLength(0).cacheControl(CacheControl.noStore())
                .body(new byte[0]);
    }

    // ------------------------------------------------------------------ 视图

    private MemberShell shell(String heading) {
        return new MemberShell(pages.member(heading), PlayerFramePolicy.create().nonce(), site.publicPath(SCRIPT_PATH));
    }

    private UserLoginForm loginForm(MemberPageSettings s) {
        return new UserLoginForm(link("login"), link("index"),
                s.loginVerify() ? Optional.of(captchaView()) : Optional.empty(),
                s.regOpen() ? Optional.of(link("reg")) : Optional.empty(), link("findpass"));
    }

    private MemberCaptcha captchaView() {
        return new MemberCaptcha(site.publicPath(VERIFY_PATH));
    }

    private SafeUrl link(String action) {
        return site.publicPath(urls.user(action));
    }

    private SafeUrl avatar(MemberProfile p) {
        return SafeUrl.parse(p.portraitUrl()).orElseGet(() -> site.publicPath("static/images/touxiang.png"));
    }

    // ------------------------------------------------------------------ 请求解析

    /** 旧 {@code captcha_check($param['verify'])}：按会话默认槽校验，通过或猜错都作废（D-B08-4）。 */
    private java.util.function.BooleanSupplier captcha(HttpServletRequest request, Map<String, PhpInput.Value> params) {
        return () -> {
            byte[] v = PhpInput.scalar(params, "verify");
            return Captchas.check(sessions.current(request), v == null ? null : new String(v, StandardCharsets.UTF_8),
                    Captchas.DEFAULT_SLOT, sessions.nowEpochSeconds());
        };
    }

    private static Map<String, PhpCookies.Value> cookies(HttpServletRequest request) {
        return PhpCookies.parse(Collections.list(request.getHeaders("Cookie")));
    }

    /** ThinkPHP {@code Request::isAjax()}：{@code X-Requested-With: XMLHttpRequest}，或参数 {@code _ajax} 为真值。 */
    private static boolean isAjax(HttpServletRequest request, Map<String, PhpInput.Value> params) {
        String h = request.getHeader("X-Requested-With");
        if (h != null && h.equalsIgnoreCase("XMLHttpRequest")) {
            return true;
        }
        if (params == null) {
            return false;
        }
        PhpInput.Value v = params.get("_ajax");
        return v != null && (v.array() || !isPhpEmpty(v.bytes()));
    }

    /**
     * 参数按 ThinkPHP {@code input()} 合并（18 文档第 3.1 节）：查询串与 urlencoded 正文按字节解析；multipart 正文的文本字段经容器读取。
     * multipart 按需解析（{@code spring.servlet.multipart.resolve-lazily}），超限或无法解析时与旧版 PHP 一样当作没有 POST 字段。
     */
    private static Map<String, PhpInput.Value> params(HttpServletRequest request, Map<String, String> pathVars) {
        Map<String, PhpInput.Value> get = PhpInput.parseQuery(request.getQueryString());
        Map<String, PhpInput.Value> post = Map.of();
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase();
            if (type.startsWith("application/x-www-form-urlencoded")) {
                post = PhpInput.parse(body(request));
            } else if (request instanceof MultipartHttpServletRequest mr) {
                Map<String, PhpInput.Value> m = new LinkedHashMap<>();
                try {
                    for (Map.Entry<String, String[]> e : mr.getParameterMap().entrySet()) {
                        String[] values = e.getValue();
                        if (values.length > 0) {
                            m.put(e.getKey(), new PhpInput.Value(
                                    values[values.length - 1].getBytes(StandardCharsets.UTF_8), false));
                        }
                    }
                } catch (MultipartException e) {
                    m.clear();
                }
                post = m;
            }
        }
        return PhpInput.merge(get, post, pathVars);
    }

    private static byte[] body(HttpServletRequest request) {
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

    private Route route(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        String pathInfo;
        if (raw.equals("/index.php/user") || raw.startsWith("/index.php/user/")) {
            pathInfo = raw.substring("/index.php/".length());
        } else if (urls.style().rewrite()) {
            pathInfo = raw.substring(1);
        } else {
            throw notFound();
        }
        if (pathInfo.length() > 1024) {
            throw notFound();
        }
        String decoded = SiteAjaxController.percentDecode(pathInfo).orElseThrow(SiteUserController::notFound);
        String p = trimSlashes(SUFFIX.matcher(decoded).replaceFirst(""));
        String[] seg = p.split("/", -1);
        String action = seg.length > 1 && !seg[1].isEmpty() ? seg[1] : "index";
        Map<String, String> vars = new LinkedHashMap<>();
        if (seg.length > 2) {
            List<String> rest = new ArrayList<>(List.of(seg).subList(2, seg.length));
            Matcher m = PATH_PAIR.matcher(String.join("|", rest));
            while (m.find()) {
                vars.put(m.group(1), m.group(2));
            }
        }
        return new Route(action, vars);
    }

    private record Route(String action, Map<String, String> pathVars) {
    }

    private static boolean truthy(PhpCookies.Value v) {
        return v != null && (v.array() || !isPhpEmpty(v.bytes()));
    }

    private static boolean isPhpEmpty(byte[] v) {
        return v.length == 0 || (v.length == 1 && v[0] == '0');
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

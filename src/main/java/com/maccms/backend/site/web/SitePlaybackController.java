package com.maccms.backend.site.web;

import com.maccms.backend.common.browser.BrowserExternalAccess;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.PasswordTarget;
import com.maccms.backend.content.application.VideoPlaybackService;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlParser;
import com.maccms.backend.site.application.PasswordAttempts;
import com.maccms.backend.site.application.PasswordTickets;
import com.maccms.backend.site.application.PlayerSettings;
import com.maccms.backend.site.application.RichTextCache;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.site.web.view.AccessNoticePage;
import com.maccms.backend.site.web.view.BuyConfirmPage;
import com.maccms.backend.site.web.view.DownPage;
import com.maccms.backend.site.web.view.DownerPage;
import com.maccms.backend.site.web.view.FrameNoticePage;
import com.maccms.backend.site.web.view.FramePasswordPage;
import com.maccms.backend.site.web.view.PasswordPage;
import com.maccms.backend.site.web.view.PlayPage;
import com.maccms.backend.site.web.view.PlayerFramePage;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 播放/下载四个入口（B05，docs/architecture/15-b05-readonly-pages.md 第 6 节）：旧 {@code vod/play}、{@code vod/player}、
 * {@code vod/down}、{@code vod/downer} 的默认 URL 形态（{@code /index.php/vod/play/id/1/sid/1/nid/1.html}，rewrite 与
 * suffix_hide 变体由 {@link LegacyUrlParser} 判定）。GET/HEAD 显示页面；播放器内页与下载地址页另接受 POST 校验访问密码（旧
 * {@code ajax/pwd} 的 1-4、1-5），通过后发放签名票据 cookie 并 303 回到 GET 地址。
 *
 * <p>外层页（play、down、downer、提示页、密码页）使用站点外壳与严格策略（无脚本）；播放器内页使用专用策略
 * （{@code PlayerFramePolicy}，D-B05-1）。所有响应 no-store、noindex。日志不记录密码、票据或 cookie。
 */
@Controller
@ConditionalOnSiteEnabled
public class SitePlaybackController {

    private static final Logger log = LoggerFactory.getLogger(SitePlaybackController.class);
    private static final Set<LegacyPage> PAGES = Set.of(LegacyPage.VOD_PLAY, LegacyPage.VOD_PLAYER, LegacyPage.VOD_DOWN,
            LegacyPage.VOD_DOWNER);
    private static final String INVALID_PASSWORD = "密码不正确，请重新输入。";

    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final LegacyUrlBuilder urls;
    private final SiteSettings site;
    private final PlaybackPages pages;
    private final VideoPlaybackService playback;
    private final PasswordTickets tickets;
    private final PasswordAttempts attempts;
    private final ObjectProvider<MemberRecordService> records;
    private final ObjectProvider<MemberWalletService> wallet;

    public SitePlaybackController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                  LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                  ContentReadService content, VideoPlaybackService playback, PlayerSettings player,
                                  BrowserExternalAccess external, PasswordTickets tickets, PasswordAttempts attempts) {
        this(renderer, sitePages, urls, parser, images, richText, content, playback, player, external, tickets, attempts,
                null, null);
    }

    @Autowired
    public SitePlaybackController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                  LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                  ContentReadService content, VideoPlaybackService playback, PlayerSettings player,
                                  BrowserExternalAccess external, PasswordTickets tickets, PasswordAttempts attempts,
                                  ObjectProvider<MemberRecordService> records,
                                  ObjectProvider<MemberWalletService> wallet) {
        this.records = records;
        this.wallet = wallet;
        this.renderer = renderer;
        this.parser = parser;
        this.urls = urls;
        this.site = sitePages.site();
        this.playback = playback;
        this.tickets = tickets;
        this.attempts = attempts;
        this.pages = new PlaybackPages(playback, content, sitePages, urls, player, external.w1(), external.w2(),
                tickets.enabled(), new ContentPages(content, sitePages, urls, images, richText));
    }

    @GetMapping({"/index.php/vod/play/**", "/vod/play/**", "/index.php/vod/player/**", "/vod/player/**",
            "/index.php/vod/down/**", "/vod/down/**", "/index.php/vod/downer/**", "/vod/downer/**"})
    public ResponseEntity<String> page(HttpServletRequest request) {
        SiteRoute.Legacy route = route(request);
        ContentMember member = ContentMember.of(request, records, wallet);
        PlaybackPages.Result result = switch (route.page()) {
            case VOD_PLAY -> pages.play(route, member);
            case VOD_PLAYER -> pages.player(route, proof(request, PasswordTarget.VIDEO_PLAY, route.id()), "", member);
            case VOD_DOWN -> pages.down(route, member);
            case VOD_DOWNER -> pages.downer(route, proof(request, PasswordTarget.VIDEO_DOWN, route.id()), "", member);
            default -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        };
        return render(result);
    }

    /** 访问密码校验（只认 POST，旧 ajax/pwd 的 GET 形式不承接）。 */
    @PostMapping({"/index.php/vod/player/**", "/vod/player/**", "/index.php/vod/downer/**", "/vod/downer/**"})
    public ResponseEntity<String> password(HttpServletRequest request,
                                           @RequestParam(name = "pwd", required = false) String pwd) {
        SiteRoute.Legacy route = route(request);
        PasswordTarget target = switch (route.page()) {
            case VOD_PLAYER -> PasswordTarget.VIDEO_PLAY;
            case VOD_DOWNER -> PasswordTarget.VIDEO_DOWN;
            default -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        };
        if (!tickets.enabled()) {
            // 没有部署密钥：不提供在线验证，与 GET 相同（密码页 403，没有表单）
            return render(show(route, null, "", ContentMember.of(request, records, wallet)));
        }
        if (!attempts.tryAcquire(target, route.id())) {
            log.warn("site password attempts limited: target={}", target.code());
            return render(pages.tooManyAttempts(route));
        }
        if (!playback.verifyPassword(route.id(), target, pwd)) {
            return render(show(route, null, INVALID_PASSWORD, ContentMember.of(request, records, wallet)));
        }
        ResponseCookie cookie = ResponseCookie.from(PasswordTickets.cookieName(target, route.id()),
                        tickets.issue(target, route.id(), pwd))
                .httpOnly(true)
                .sameSite("Lax")
                .secure(site.baseUrl().href().startsWith("https:"))
                .path(site.homePath().href())
                .maxAge(tickets.maxAgeSeconds())
                .build();
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, site.publicPath(urls.legacy(route)).href())
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex")
                .build();
    }

    private PlaybackPages.Result show(SiteRoute.Legacy route, PasswordProof proof, String error,
                                      ContentMember member) {
        return route.page() == LegacyPage.VOD_PLAYER ? pages.player(route, proof, error, member)
                : pages.downer(route, proof, error, member);
    }

    private SiteRoute.Legacy route(HttpServletRequest request) {
        // getRequestURI 是未解码的原始路径；应用挂在根路径（base-url 子目录由反向代理映射，05 文档第 2 节）
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        String query = "POST".equals(request.getMethod()) ? null : request.getQueryString();
        SiteRoute route = parser.parse(rawPath, query)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"))
                .route();
        if (!(route instanceof SiteRoute.Legacy l) || !PAGES.contains(l.page())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        return l;
    }

    /** 签名票据 cookie → 交给 content 的凭据；同名 cookie 可能有多个（不同 Path），逐个尝试。 */
    private PasswordProof proof(HttpServletRequest request, PasswordTarget target, long id) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || !tickets.enabled()) {
            return null;
        }
        String name = PasswordTickets.cookieName(target, id);
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                Optional<PasswordProof> p = tickets.verify(target, id, c.getValue());
                if (p.isPresent()) {
                    return p.get();
                }
            }
        }
        return null;
    }

    private ResponseEntity<String> render(PlaybackPages.Result result) {
        return switch (result) {
            case PlaybackPages.Page p -> renderer.noStore(p.status(), tpl -> switch (p.view()) {
                case PlayPage v -> tpl.sitePagesPlay(v);
                case DownPage v -> tpl.sitePagesDown(v);
                case DownerPage v -> tpl.sitePagesDowner(v);
                case AccessNoticePage v -> tpl.sitePagesAccessNotice(v);
                case BuyConfirmPage v -> tpl.sitePagesBuyConfirm(v);
                case PasswordPage v -> tpl.sitePagesPassword(v);
                default -> throw new IllegalStateException("未知页面");
            });
            case PlaybackPages.Frame f -> renderer.playerFrame(f.status(), tpl -> switch (f.view()) {
                case PlayerFramePage v -> tpl.sitePlayerPlayer(v);
                case FrameNoticePage v -> tpl.sitePlayerNotice(v);
                case FramePasswordPage v -> tpl.sitePlayerPassword(v);
                default -> throw new IllegalStateException("未知页面");
            }, f.policy());
        };
    }
}

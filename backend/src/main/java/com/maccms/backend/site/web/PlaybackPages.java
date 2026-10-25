package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.ContentViewer;
import com.maccms.backend.content.application.LegacyMultiValue;
import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.PlaybackEntry;
import com.maccms.backend.content.application.VideoPlaybackService;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.content.application.view.VideoMediaDto;
import com.maccms.backend.content.application.view.VideoPlaybackResult;
import com.maccms.backend.site.application.BrowserAddress;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyPlayerData;
import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.PhpJson;
import com.maccms.backend.site.application.PlayerFramePolicy;
import com.maccms.backend.site.application.PlayerSettings;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.AccessNoticePage;
import com.maccms.backend.site.web.view.BuyConfirmPage;
import com.maccms.backend.site.web.view.ContentForm;
import com.maccms.backend.site.web.view.DownPage;
import com.maccms.backend.site.web.view.DownerPage;
import com.maccms.backend.site.web.view.EpisodeLink;
import com.maccms.backend.site.web.view.FrameNoticePage;
import com.maccms.backend.site.web.view.FramePasswordPage;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.PasswordPage;
import com.maccms.backend.site.web.view.PlayPage;
import com.maccms.backend.site.web.view.PlaySourceView;
import com.maccms.backend.site.web.view.PlayerFrame;
import com.maccms.backend.site.web.view.PlayerFramePage;
import com.maccms.backend.site.web.view.TrialOffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * 播放/下载四个入口（旧 vod/play、vod/player、vod/down、vod/downer，B05）的视图模型组装。授权判定只来自 content 的
 * {@link VideoPlaybackService}；本类只决定"判定通过后哪些地址可以交给浏览器"（W1/W2 与协议白名单），规则见
 * docs/architecture/15-b05-readonly-pages.md 第 6 节。
 *
 * <p>每个方法返回 {@link Result}：要渲染的页面与 HTTP 状态。视频不存在、未审核、分类不可见，或 sid/nid 不在播放/下载分组中时抛
 * NOT_FOUND（404）。
 */
public class PlaybackPages {

    /** 关闭态与地址不可用的固定文案（队长定稿的三句，15 文档第 6.3 节；最后一句为新版的协议白名单）。 */
    static final String W1_CLOSED = "该播放源需要外部解析服务，本站当前未开放。";
    static final String W2_PLAY_CLOSED = "该地址位于外部站点，本站当前未开放外部播放。";
    static final String W2_DOWN_CLOSED = "该地址位于外部站点，本站当前未开放外部下载。";
    static final String UNUSABLE = "该集的地址无法使用。";
    static final String NO_PLAY_PERMISSION = "当前访客身份没有观看该视频的权限。";
    static final String NO_DOWN_PERMISSION = "当前访客身份没有下载该视频的权限。";
    static final String PLAY_POINTS = "该视频为收费内容，需要会员积分才能观看。";
    static final String DOWN_POINTS = "该视频的下载为收费内容，需要会员积分。";
    static final String COPYRIGHT = "该视频由于版权限制，暂不提供播放。";
    static final String PASSWORD_ONLINE = "请输入访问密码。";
    static final String PASSWORD_OFFLINE = "该内容需要访问密码，本站暂不支持在线验证。";
    /** 旧 lang('controller/charge_data')（Base.php:174-176，下载、组 1、积分 &gt; 0）。 */
    static final String CHARGE_DATA = "此页面为收费数据，请先登录后访问！";

    /** 旧 player.js 会以 document.write 写出 static/player/&lt;来源&gt;.js，来源编码须是安全的文件名。 */
    private static final Pattern PLAYER_CODE = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** 页面与状态。 */
    public sealed interface Result {
    }

    /** 外层页面（使用站点外壳与严格策略）。 */
    public record Page(HttpStatus status, Object view) implements Result {
    }

    /** 播放器内页（专用策略）。 */
    public record Frame(HttpStatus status, Object view, String policy) implements Result {
    }

    private final VideoPlaybackService playback;
    private final ContentReadService content;
    private final SitePages pages;
    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final PlayerSettings player;
    private final boolean w1;
    private final boolean w2;
    private final boolean passwordsOnline;
    private final ContentPages contentPages;

    public PlaybackPages(VideoPlaybackService playback, ContentReadService content, SitePages pages,
                         LegacyUrlBuilder urls, PlayerSettings player, boolean w1, boolean w2, boolean passwordsOnline,
                         ContentPages contentPages) {
        this.playback = playback;
        this.content = content;
        this.pages = pages;
        this.site = pages.site();
        this.urls = urls;
        this.player = player;
        this.w1 = w1;
        this.w2 = w2;
        this.passwordsOnline = passwordsOnline;
        this.contentPages = contentPages;
    }

    // ------------------------------------------------------------------ vod/play

    public Result play(SiteRoute.Legacy route) {
        return play(route, ContentMember.GUEST);
    }

    /**
     * @param member 本请求的会员（B10）：需要积分时给已登录会员购买确认表单，试看时给购买入口，放行或试看时记一笔播放记录
     */
    Result play(SiteRoute.Legacy route, ContentMember member) {
        requireAvailable();
        VideoPlaybackResult r = playback.access(route.id(), route.sid(), route.nid(), PlaybackEntry.PLAY_PAGE, null);
        List<CategoryDto> roots = content.listCategories().items();
        return switch (r.outcome()) {
            case NO_PERMISSION -> notice(r, roots, "无法观看", NO_PLAY_PERMISSION);
            case POINTS_REQUIRED -> confirm(r, roots, route, false, member);
            case COPYRIGHT_RESTRICTED -> notice(r, roots, "版权限制", COPYRIGHT);
            case PASSWORD_REQUIRED -> throw new IllegalStateException("播放页不检查密码");
            case ALLOWED, TRIAL -> {
                List<LegacyMultiValue.PlaySource> sources = sources(r, false);
                LegacyMultiValue.PlaySource source = source(sources, route.sid());
                LegacyMultiValue.PlayEpisode episode = episode(source, route.nid());
                String name = text(r.name());
                List<NavLink> crumbs = breadcrumb(r, roots);
                crumbs.add(new NavLink(episode.name().isEmpty() ? "播放" : text(episode.name()),
                        link(LegacyPage.VOD_PLAY, r.id(), route.sid(), route.nid()), true));
                int index = source.episodes().indexOf(episode);
                Optional<SafeUrl> previous = index > 0 ? Optional.of(link(LegacyPage.VOD_PLAY, r.id(), source.sid(),
                        source.episodes().get(index - 1).nid())) : Optional.empty();
                Optional<SafeUrl> next = index < source.episodes().size() - 1 ? Optional.of(link(LegacyPage.VOD_PLAY,
                        r.id(), source.sid(), source.episodes().get(index + 1).nid())) : Optional.empty();
                String current = sourceLabel(source) + " · " + text(episode.name());
                String title = name + " " + text(episode.name());
                member.recordView(4, r.id(), route.sid(), route.nid());
                Optional<TrialOffer> offer = r.outcome() == PlaybackOutcome.TRIAL && member.loggedIn()
                        ? Optional.of(trialOffer(r, route, member.points())) : Optional.empty();
                yield new Page(HttpStatus.OK, new PlayPage(
                        pages.content(title + " - " + site.name(), "", "",
                                site.canonical(urls.legacy(SiteRoute.Legacy.episode(LegacyPage.VOD_PLAY, r.id(),
                                        route.sid(), route.nid()))), contentPages.nav(roots, rootOf(roots, r))),
                        crumbs, name, current, sourceViews(sources, LegacyPage.VOD_PLAY, r.id(), route.sid(),
                        route.nid(), false), previous, next,
                        link(LegacyPage.VOD_PLAYER, r.id(), route.sid(), route.nid()), name + " 播放器",
                        site.publicPath(urls.vodDetail(r.id())), r.outcome() == PlaybackOutcome.TRIAL, offer));
            }
        };
    }

    // ------------------------------------------------------------------ vod/player

    /**
     * @param proof 播放密码凭据（签名 cookie 校验通过后），可为 null
     * @param error 密码页的错误提示（POST 失败时），可为空串
     */
    public Result player(SiteRoute.Legacy route, PasswordProof proof, String error) {
        return player(route, proof, error, ContentMember.GUEST);
    }

    /** @param member 本请求的会员（B10）：试看结束时给已登录会员旧版 5001/5002 的提示 */
    Result player(SiteRoute.Legacy route, PasswordProof proof, String error, ContentMember member) {
        requireAvailable();
        VideoPlaybackResult r = playback.access(route.id(), route.sid(), route.nid(), PlaybackEntry.PLAYER, proof);
        PlayerFrame frame = new PlayerFrame(text(r.name()) + " - " + site.name(), stylesheet());
        return switch (r.outcome()) {
            case NO_PERMISSION -> frameNotice(frame, HttpStatus.FORBIDDEN, "无法观看",
                    NO_PLAY_PERMISSION);
            case POINTS_REQUIRED -> frameNotice(frame, HttpStatus.FORBIDDEN, "需要积分",
                    PLAY_POINTS);
            case COPYRIGHT_RESTRICTED -> frameNotice(frame, HttpStatus.FORBIDDEN, "版权限制",
                    COPYRIGHT);
            case PASSWORD_REQUIRED -> new Frame(HttpStatus.FORBIDDEN,
                    new FramePasswordPage(frame, "需要播放密码", passwordsOnline ? PASSWORD_ONLINE : PASSWORD_OFFLINE,
                            passwordsOnline ? Optional.of(link(LegacyPage.VOD_PLAYER, r.id(), route.sid(), route.nid()))
                                    : Optional.empty(), error), strictFramePolicy());
            case ALLOWED, TRIAL -> playerFrame(route, r, frame, r.outcome() == PlaybackOutcome.TRIAL && member.loggedIn()
                    ? trialOffer(r, route, member.points()).message() : "");
        };
    }

    private Result playerFrame(SiteRoute.Legacy route, VideoPlaybackResult r, PlayerFrame frame,
                               String trialEndMessage) {
        List<LegacyMultiValue.PlaySource> sources = sources(r, false);
        LegacyMultiValue.PlaySource source = source(sources, route.sid());
        LegacyMultiValue.PlayEpisode episode = episode(source, route.nid());
        HttpStatus ok = HttpStatus.OK;
        // 旧 player_info['from']：集自己的来源优先于组来源（All.php:526-529），LegacyMultiValue 已按此给出
        String from = episode.from();
        if (!PLAYER_CODE.matcher(from).matches()) {
            return frameNotice(frame, ok, "无法播放", UNUSABLE);
        }
        PlayerFramePolicy policy = PlayerFramePolicy.create();
        Optional<String> parse = player.parseFor(from);
        if (parse.isPresent()) {
            BrowserAddress.Result p = BrowserAddress.classify(parse.get(), player.siteHost());
            if (p.kind() == BrowserAddress.Kind.UNSAFE || p.kind() == BrowserAddress.Kind.OTHER_SCHEME) {
                return frameNotice(frame, ok, "无法播放", UNUSABLE);
            }
            if (p.external()) {
                if (!w1) {
                    return frameNotice(frame, ok, "无法播放", W1_CLOSED);
                }
                p.cspSource().ifPresent(policy::allowFrame);
            }
        } else if (from.equals("iva")) {
            // static/player/iva.js:2 加载 videojj 的外部脚本（W1 的既定功能例外）
            if (!w1) {
                return frameNotice(frame, ok, "无法播放", W1_CLOSED);
            }
            policy.allowIva();
        }
        if (w1) {
            for (String v : player.prestrainAndBuffer()) {
                BrowserAddress.Result b = BrowserAddress.classify(v, player.siteHost());
                if (b.kind() == BrowserAddress.Kind.EXTERNAL) {
                    b.cspSource().ifPresent(policy::allowFrame);
                }
            }
        }
        String url = withUploadPrefix(episode.url());
        BrowserAddress.Result address = BrowserAddress.classify(url, player.siteHost());
        if (address.kind() == BrowserAddress.Kind.EMPTY || address.kind() == BrowserAddress.Kind.UNSAFE) {
            return frameNotice(frame, ok, "无法播放", UNUSABLE);
        }
        if (address.external()) {
            if (!w2) {
                return frameNotice(frame, ok, "无法播放", W2_PLAY_CLOSED);
            }
            address.cspSource().ifPresent(policy::allowMedia);
        }
        String urlNext = withUploadPrefix(urlAt(source, route.nid() + 1));
        BrowserAddress.Result next = BrowserAddress.classify(urlNext, player.siteHost());
        if (next.kind() == BrowserAddress.Kind.UNSAFE || (next.external() && !w2)) {
            urlNext = "";
        }
        String link = href(LegacyPage.VOD_PLAY, r.id(), 1, 1);
        String linkPre = route.nid() > 1 ? href(LegacyPage.VOD_PLAY, r.id(), route.sid(), route.nid() - 1) : "";
        String linkNext = route.nid() < source.urlCount()
                ? href(LegacyPage.VOD_PLAY, r.id(), route.sid(), route.nid() + 1) : "";
        VideoMediaDto media = r.media().orElseThrow();
        Map<String, Object> playerAaaa = LegacyPlayerData.build("play", player.encrypt(), r.trySeeMinutes(),
                media.points(), link, linkNext, linkPre, r.name(), r.actor(), r.director(), r.classes(), url, urlNext,
                from, source.server(), source.note(), Long.toString(r.id()), route.sid(), route.nid());
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> maccms = new LinkedHashMap<>();
        // 旧模板的 maccms.path：MAC_PATH 去掉末尾的 /（static/js/player.js:2 拼 maccms.path+'/static/player/'）
        String home = site.homePath().href();
        maccms.put("path", home.substring(0, home.length() - 1));
        data.put("maccms", maccms);
        data.put("player", playerAaaa);
        List<SafeUrl> scripts = List.of(site.publicPath(SitePages.PLAYER_BOOT_PATH),
                site.publicPath("static/js/jquery.js"), site.publicPath(SitePages.PLAYER_CONFIG_PATH),
                site.publicPath("static/js/player.js"));
        int trial = r.trialMinutes();
        Optional<SafeUrl> trialScript = trial > 0 ? Optional.of(site.publicPath(SitePages.TRIAL_SCRIPT_PATH))
                : Optional.empty();
        return new Frame(ok, new PlayerFramePage(frame, policy.nonce(), PhpJson.encode(data), trial, scripts,
                trialScript, trial > 0 ? trialEndMessage : ""), policy.header());
    }

    // ------------------------------------------------------------------ vod/down、vod/downer

    public Result down(SiteRoute.Legacy route) {
        return down(route, ContentMember.GUEST);
    }

    /** @param member 本请求的会员（B10）：需要积分时给购买确认，放行时记一笔下载记录 */
    Result down(SiteRoute.Legacy route, ContentMember member) {
        requireAvailable();
        VideoPlaybackResult r = playback.access(route.id(), route.sid(), route.nid(), PlaybackEntry.DOWN_PAGE, null);
        List<CategoryDto> roots = content.listCategories().items();
        return switch (r.outcome()) {
            case NO_PERMISSION -> notice(r, roots, "无法下载", NO_DOWN_PERMISSION);
            case POINTS_REQUIRED -> confirm(r, roots, route, true, member);
            case COPYRIGHT_RESTRICTED, PASSWORD_REQUIRED, TRIAL -> throw new IllegalStateException("下载页的判定只有权限与积分");
            case ALLOWED -> {
                List<LegacyMultiValue.PlaySource> sources = sources(r, true);
                episode(source(sources, route.sid()), route.nid());
                String name = text(r.name());
                List<NavLink> crumbs = breadcrumb(r, roots);
                crumbs.add(new NavLink("下载", link(LegacyPage.VOD_DOWN, r.id(), route.sid(), route.nid()), true));
                member.recordView(5, r.id(), route.sid(), route.nid());
                yield new Page(HttpStatus.OK, new DownPage(
                        pages.content(name + " 下载 - " + site.name(), "", "",
                                site.canonical(urls.legacy(SiteRoute.Legacy.episode(LegacyPage.VOD_DOWN, r.id(),
                                        route.sid(), route.nid()))), contentPages.nav(roots, rootOf(roots, r))),
                        crumbs, name, sourceViews(sources, LegacyPage.VOD_DOWNER, r.id(), route.sid(), route.nid(), true),
                        site.publicPath(urls.vodDetail(r.id()))));
            }
        };
    }

    public Result downer(SiteRoute.Legacy route, PasswordProof proof, String error) {
        return downer(route, proof, error, ContentMember.GUEST);
    }

    /** @param member 本请求的会员（B10）：需要积分时给购买确认 */
    Result downer(SiteRoute.Legacy route, PasswordProof proof, String error, ContentMember member) {
        requireAvailable();
        VideoPlaybackResult r = playback.access(route.id(), route.sid(), route.nid(), PlaybackEntry.DOWNER, proof);
        List<CategoryDto> roots = content.listCategories().items();
        return switch (r.outcome()) {
            case NO_PERMISSION -> notice(r, roots, "无法下载", NO_DOWN_PERMISSION);
            case POINTS_REQUIRED -> confirm(r, roots, route, true, member);
            case COPYRIGHT_RESTRICTED, TRIAL -> throw new IllegalStateException("下载地址页没有版权与试看判定");
            case PASSWORD_REQUIRED -> new Page(HttpStatus.FORBIDDEN, new PasswordPage(
                    pages.content("需要下载密码 - " + site.name(), "", "",
                            site.canonical(urls.legacy(route)), List.of()),
                    "需要下载密码", passwordsOnline ? PASSWORD_ONLINE : PASSWORD_OFFLINE,
                    passwordsOnline ? Optional.of(link(LegacyPage.VOD_DOWNER, r.id(), route.sid(), route.nid()))
                            : Optional.empty(), error, Optional.of(site.publicPath(urls.vodDetail(r.id())))));
            case ALLOWED -> {
                List<LegacyMultiValue.PlaySource> sources = sources(r, true);
                LegacyMultiValue.PlaySource source = source(sources, route.sid());
                LegacyMultiValue.PlayEpisode episode = episode(source, route.nid());
                String name = text(r.name());
                List<NavLink> crumbs = breadcrumb(r, roots);
                SafeUrl downUrl = link(LegacyPage.VOD_DOWN, r.id(), route.sid(), route.nid());
                crumbs.add(new NavLink("下载", downUrl, false));
                crumbs.add(new NavLink(text(episode.name()), link(LegacyPage.VOD_DOWNER, r.id(), route.sid(),
                        route.nid()), true));
                String url = withUploadPrefix(episode.url());
                BrowserAddress.Result a = BrowserAddress.classify(url, player.siteHost());
                Optional<SafeUrl> href = Optional.empty();
                String text = "";
                String notice = "";
                if (a.kind() == BrowserAddress.Kind.EMPTY || a.kind() == BrowserAddress.Kind.UNSAFE) {
                    notice = UNUSABLE;
                } else if (a.external() && !w2) {
                    notice = W2_DOWN_CLOSED;
                } else {
                    href = SafeUrl.parse(url.startsWith("//") ? schemeOfBase() + ":" + url : url);
                    if (href.isEmpty()) {
                        text = url;
                    }
                }
                yield new Page(HttpStatus.OK, new DownerPage(
                        pages.content(name + " " + text(episode.name()) + " 下载 - " + site.name(), "", "",
                                site.canonical(urls.legacy(route)), contentPages.nav(roots, rootOf(roots, r))),
                        crumbs, name, sourceLabel(source) + " · " + text(episode.name()), href, text, notice,
                        downUrl));
            }
        };
    }

    /** 密码尝试过于频繁（429）。 */
    public Result tooManyAttempts(SiteRoute.Legacy route) {
        String message = "尝试次数过多，请稍后再试。";
        if (route.page() == LegacyPage.VOD_PLAYER) {
            return new Frame(HttpStatus.TOO_MANY_REQUESTS,
                    new FrameNoticePage(new PlayerFrame("请稍后再试 - " + site.name(), stylesheet()), "请稍后再试",
                            message), strictFramePolicy());
        }
        return new Page(HttpStatus.TOO_MANY_REQUESTS, new AccessNoticePage(
                pages.content("请稍后再试 - " + site.name(), "", "", site.canonical(urls.legacy(route)), List.of()),
                "请稍后再试", message, Optional.empty()));
    }

    // ------------------------------------------------------------------ 共用

    private void requireAvailable() {
        if (!player.available()) {
            // 没有旧播放器配置（未设置 maccms.legacy-config.dir）：无法按旧版解码播放分组，页面不提供
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
    }

    private List<LegacyMultiValue.PlaySource> sources(VideoPlaybackResult r, boolean down) {
        VideoMediaDto m = r.media().orElseThrow();
        return LegacyMultiValue.playSources(m.from(), m.url(), m.server(), m.note(),
                down ? player.downers() : player.players(), player.servers(), player.playerSort());
    }

    private static LegacyMultiValue.PlaySource source(List<LegacyMultiValue.PlaySource> sources, int sid) {
        return sources.stream().filter(s -> s.sid() == sid).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"));
    }

    private static LegacyMultiValue.PlayEpisode episode(LegacyMultiValue.PlaySource source, int nid) {
        return source.episodes().stream().filter(e -> e.nid() == nid).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "页面不存在"));
    }

    /** 旧 {@code $info[...]['urls'][$nid+1]['url']}：按位置取，该位置没有集时为空串（All.php:517）。 */
    private static String urlAt(LegacyMultiValue.PlaySource source, int nid) {
        return source.episodes().stream().filter(e -> e.nid() == nid).map(LegacyMultiValue.PlayEpisode::url)
                .findFirst().orElse("");
    }

    /** 旧版 {@code upload} 开头的地址前面拼 MAC_PATH（All.php:519-524）；新版拼 base-url 的路径部分。 */
    private String withUploadPrefix(String url) {
        return url.startsWith("upload") ? site.homePath().href() + url : url;
    }

    private List<PlaySourceView> sourceViews(List<LegacyMultiValue.PlaySource> sources, LegacyPage target, long id,
                                             int sid, int nid, boolean down) {
        List<PlaySourceView> out = new ArrayList<>();
        for (LegacyMultiValue.PlaySource s : sources) {
            List<EpisodeLink> episodes = new ArrayList<>();
            for (LegacyMultiValue.PlayEpisode e : s.episodes()) {
                episodes.add(new EpisodeLink(text(e.name()), link(target, id, s.sid(), e.nid()),
                        s.sid() == sid && e.nid() == nid));
            }
            out.add(new PlaySourceView("source-" + s.sid(), sourceLabel(s), text(s.note()), s.sid() == sid,
                    List.copyOf(episodes)));
        }
        return List.copyOf(out);
    }

    /** 分组名称：播放器/下载器配置的 show（旧模板常用 {$vo.player_info.show}），为空时用来源编码。 */
    private static String sourceLabel(LegacyMultiValue.PlaySource s) {
        String show = s.player().getOrDefault("show", "");
        return show.isEmpty() ? s.from() : show;
    }

    private List<NavLink> breadcrumb(VideoPlaybackResult r, List<CategoryDto> roots) {
        List<NavLink> crumbs = new ArrayList<>();
        crumbs.add(new NavLink("首页", site.homePath(), false));
        List<CategoryDto> chain = ContentPages.chain(roots, r.category().id());
        for (CategoryDto c : chain) {
            crumbs.add(new NavLink(text(c.name()), site.publicPath(urls.vodType(c.id(), 1)), false));
        }
        crumbs.add(new NavLink(text(r.name()), site.publicPath(urls.vodDetail(r.id())), false));
        return crumbs;
    }

    private static int rootOf(List<CategoryDto> roots, VideoPlaybackResult r) {
        List<CategoryDto> chain = ContentPages.chain(roots, r.category().id());
        return chain.isEmpty() ? 0 : chain.getFirst().id();
    }

    private Result notice(VideoPlaybackResult r, List<CategoryDto> roots, String heading, String message) {
        return new Page(HttpStatus.FORBIDDEN, new AccessNoticePage(
                pages.content(heading + " - " + site.name(), "", "", site.canonical(urls.vodDetail(r.id())),
                        contentPages.nav(roots, rootOf(roots, r))),
                heading, message, Optional.of(site.publicPath(urls.vodDetail(r.id())))));
    }

    /**
     * 收费数据的购买确认（B10，旧 vod/confirm 与 3003/4003/4001，19 文档第 5.2 节）：403、严格策略。下载且组 1 时旧版直接要求登录
     * （4001，不能购买）；其余为"需要支付【N】积分，确认支付吗？"，已登录会员带购买表单，游客带登录链接。
     */
    private Result confirm(VideoPlaybackResult r, List<CategoryDto> roots, SiteRoute.Legacy route, boolean down,
                           ContentMember member) {
        Optional<ContentForm> form = Optional.empty();
        String message;
        if (down && ContentViewer.groupId() == 1) {
            message = CHARGE_DATA;
        } else {
            message = (down ? "下载此数据，需要支付【" : "观看此数据，需要支付【") + r.points() + "】积分，确认支付吗？";
            if (member.loggedIn()) {
                form = Optional.of(buyForm(r.id(), route.sid(), route.nid(), down ? 5 : 4));
            }
        }
        Optional<SafeUrl> login = member.loggedIn() ? Optional.empty() : Optional.of(site.publicPath(urls.user("login")));
        return new Page(HttpStatus.FORBIDDEN, new BuyConfirmPage(
                pages.content("需要积分 - " + site.name(), "", "", site.canonical(urls.vodDetail(r.id())),
                        contentPages.nav(roots, rootOf(roots, r))),
                "需要积分", message, form, login, Optional.of(site.publicPath(urls.vodDetail(r.id())))));
    }

    /**
     * 试看页给已登录会员的购买入口（旧 Base.php:206-217 的 5001/5002，19 文档第 5.3 节）：积分足够时是购买表单，不够时是充值页链接。
     */
    private TrialOffer trialOffer(VideoPlaybackResult r, SiteRoute.Legacy route, long have) {
        if (r.points() <= have) {
            return new TrialOffer("试看结束,是否支付[" + r.points() + "]积分观看完整数据？您还剩下[" + have + "]积分，请先充值！",
                    Optional.of(buyForm(r.id(), route.sid(), route.nid(), 4)), Optional.empty());
        }
        return new TrialOffer("对不起,观看此页面数据需要[" + r.points() + "]积分，您还剩下[" + have + "]积分，请先充值！",
                Optional.empty(), Optional.of(site.publicPath(urls.user("buy"))));
    }

    /** 提交到 {@code user/ajax_buy_popedom} 的普通表单（非 ajax，结果是提示页）。 */
    private ContentForm buyForm(long id, int sid, int nid, int type) {
        return ContentForms.buy(site, urls, 1, id, sid, nid, type);
    }

    private static Result frameNotice(PlayerFrame frame, HttpStatus status, String heading,
                                      String message) {
        return new Frame(status, new FrameNoticePage(frame, heading, message), strictFramePolicy());
    }

    /** 内页的提示与密码页没有脚本，使用严格策略（与其他页面相同）。 */
    private static String strictFramePolicy() {
        return SiteHtmlRenderer.CONTENT_SECURITY_POLICY;
    }

    private SafeUrl stylesheet() {
        return site.publicPath(SitePages.STYLESHEET_PATH);
    }

    private SafeUrl link(LegacyPage page, long id, int sid, int nid) {
        return site.publicPath(urls.legacy(SiteRoute.Legacy.episode(page, id, sid, nid)));
    }

    private String href(LegacyPage page, long id, int sid, int nid) {
        return link(page, id, sid, nid).href();
    }

    private String schemeOfBase() {
        String b = site.baseUrl().href();
        return b.substring(0, b.indexOf(':'));
    }

    private static String text(String raw) {
        return LegacyTextEntities.decodeOnce(raw);
    }
}

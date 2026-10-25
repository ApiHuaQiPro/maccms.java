package com.maccms.backend.site.web;

import com.maccms.backend.comment.application.CommentService;
import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.content.application.ContentCounterService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyParams;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.QrcodeImages;
import com.maccms.backend.site.application.SiteMiscSettings;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 旧 index 模块的 {@code ajax/data}、{@code ajax/suggest}、{@code ajax/desktop}、{@code qrcode/index} 与自定义标签页 {@code label/*}
 * （B06，docs/architecture/16-b06-misc-pages-provide.md 第 4.3、4.7–4.10 节）。GET/HEAD/POST（旧版不区分方法）。
 *
 * <p>地址：{@code /index.php/<控制器>/<操作>[.html][/键/值…]}（{@code rewrite} 打开时另认去掉 {@code /index.php} 的形态）；路径按
 * PATH_INFO 解码一次。B11 起另有 {@code hits}、{@code digg}、{@code score}、{@code referer}、{@code verify_check}（{@link SiteCounterActions}，
 * 20 文档第 5、7 节）；{@code pwd} 由 B05 的密码页承接（POST + 签名票据），这里仍为 404。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteAjaxController {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(?:html|htm|shtm|shtml|xml)$");
    private static final String SHORTCUT_TEMPLATE = "[InternetShortcut]\r\n        URL=%s\r\n        IDList=\r\n"
            + "        IconIndex=1\r\n        [{000214A0-0000-0000-C000-000000000046}]\r\n        Prop3=19,2";

    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final SiteHtmlRenderer renderer;
    private final SitePages sitePages;
    private final AjaxPages ajax;
    private final ContentReadService content;
    private final SiteMiscSettings misc;
    private final boolean siteSearch;
    /** B11 的计数与验证码接口；只装配了部分 Bean 的测试中为 null（这些操作按 404）。 */
    private final SiteCounterActions counters;

    public SiteAjaxController(SitePages sitePages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer,
                              LegacyRowReadService rows, ImageUrlNormalizer images, ContentReadService content,
                              SiteMiscSettings misc,
                              @Value("${maccms.site.search.enabled:false}") String siteSearch) {
        this(sitePages, urls, renderer, rows, images, content, misc, siteSearch, null);
    }

    @Autowired
    public SiteAjaxController(SitePages sitePages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer,
                              LegacyRowReadService rows, ImageUrlNormalizer images, ContentReadService content,
                              SiteMiscSettings misc,
                              @Value("${maccms.site.search.enabled:false}") String siteSearch,
                              ObjectProvider<SiteSessions> sessions, ObjectProvider<ClientIpResolver> clientIp,
                              ObjectProvider<ContentCounterService> counterService,
                              ObjectProvider<CommentService> comments, ObjectProvider<MemberReferralService> referral,
                              ObjectProvider<LegacyConfigService> legacy) {
        this(sitePages, urls, renderer, rows, images, content, misc, siteSearch,
                new SiteCounterActions(sitePages, renderer, sessions, clientIp, counterService, comments, referral,
                        legacy.getIfAvailable()));
    }

    private SiteAjaxController(SitePages sitePages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer,
                               LegacyRowReadService rows, ImageUrlNormalizer images, ContentReadService content,
                               SiteMiscSettings misc, String siteSearch, SiteCounterActions counters) {
        this.counters = counters;
        this.site = sitePages.site();
        this.sitePages = sitePages;
        this.urls = urls;
        this.renderer = renderer;
        this.ajax = new AjaxPages(rows, site, urls, images);
        this.content = content;
        this.misc = misc;
        this.siteSearch = "true".equalsIgnoreCase(siteSearch == null ? "" : siteSearch.strip());
    }

    @RequestMapping(value = {"/index.php/ajax/**", "/ajax/**", "/index.php/qrcode", "/index.php/qrcode.html",
            "/index.php/qrcode/**", "/qrcode", "/qrcode.html", "/qrcode/**", "/index.php/label/**", "/label/**"},
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<?> handle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
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
        String decoded = percentDecode(pathInfo).orElseThrow(SiteAjaxController::notFound);
        String p = trimSlashes(SUFFIX.matcher(decoded).replaceFirst(""));
        String[] seg = p.split("/", -1);
        String controller = seg[0];
        String action = seg.length > 1 ? seg[1] : "index";
        List<String> rest = seg.length > 2 ? List.of(seg).subList(2, seg.length) : List.of();
        if (controller.equals("label")) {
            // 旧版按模板语言执行 template/<模板>/label/<文件>.html；新版不读取、不执行任何模板文件，内置标签页尚无（D9-a），一律"非法请求"
            return renderer.error(HttpStatus.NOT_FOUND, sitePages.illegalRequest(), null);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            if (e.getKey().indexOf('[') >= 0) {
                return text(HttpStatus.BAD_REQUEST, "参数错误");
            }
            String[] values = e.getValue();
            params.put(e.getKey(), values.length == 0 ? "" : values[values.length - 1]);
        }
        if (controller.equals("qrcode") && action.equals("index")) {
            return qrcode(LegacyParams.thinkInput(rest, params));
        }
        if (!controller.equals("ajax")) {
            throw notFound();
        }
        LegacyParams lp = LegacyParams.of(rest, params);
        return switch (action) {
            case "data" -> json(() -> ajax.data(lp), CacheControl.noCache());
            case "suggest" -> {
                // 旧 app.search != 1 时关闭（Ajax.php:90）；新版另要求搜索页与包含匹配开关（16 文档第 4.9 节）
                if (!misc.legacySearch() || !siteSearch || !content.searchEnabled()) {
                    yield json(() -> AjaxPages.code(999, "联想搜索功能关闭中"), CacheControl.noStore());
                }
                yield json(() -> ajax.suggest(lp), CacheControl.noStore());
            }
            case "desktop" -> desktop(lp);
            case "hits", "digg", "score", "referer", "verify_check" -> counter(action, request, response, lp, params);
            default -> throw notFound();
        };
    }

    /** B11 的计数与验证码接口（旧 Ajax.php:152-472）。ajax 判定同 ThinkPHP {@code Request::isAjax()}。 */
    private ResponseEntity<?> counter(String action, HttpServletRequest request,
                                      jakarta.servlet.http.HttpServletResponse response, LegacyParams lp,
                                      Map<String, String> params) {
        if (counters == null) {
            throw notFound();
        }
        String h = request.getHeader("X-Requested-With");
        String flag = params.get("_ajax");
        boolean ajax = (h != null && h.equalsIgnoreCase("XMLHttpRequest"))
                || (flag != null && !flag.isEmpty() && !flag.equals("0"));
        return switch (action) {
            case "hits" -> counters.hits(request, lp, ajax);
            case "digg" -> counters.digg(request, lp, ajax);
            case "score" -> counters.score(request, lp, ajax);
            case "referer" -> counters.referer(request, lp, ajax);
            default -> counters.verifyCheck(request, response, lp.raw("type"), lp.raw("verify"), lp.raw("back"),
                    ajax);
        };
    }

    private ResponseEntity<byte[]> json(java.util.function.Supplier<Map<String, Object>> body, CacheControl cache) {
        Map<String, Object> out;
        HttpStatus status = HttpStatus.OK;
        try {
            out = body.get();
        } catch (ApiException e) {
            if (e.code() != ErrorCode.SERVICE_UNAVAILABLE) {
                throw e;
            }
            out = AjaxPages.code(503, "服务暂时不可用");
            status = HttpStatus.SERVICE_UNAVAILABLE;
            cache = CacheControl.noStore();
        }
        byte[] bytes = LegacyJson.encode(out).getBytes(StandardCharsets.UTF_8);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).contentType(JSON).contentLength(bytes.length)
                .cacheControl(cache).header("X-Content-Type-Options", "nosniff");
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            b.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return b.body(bytes);
    }

    /** 旧 Ajax::desktop（Ajax.php:123-150）：.url 快捷方式正文逐字节相同；名称、地址含控制字符时 400（防止注入快捷方式的行）。 */
    private ResponseEntity<?> desktop(LegacyParams p) {
        Optional<String> nameValue = p.text("name");
        Optional<String> urlValue = p.text("url");
        if (nameValue.isEmpty() || urlValue.isEmpty()) {
            return text(HttpStatus.BAD_REQUEST, "参数错误");
        }
        String name = nameValue.get();
        String url = urlValue.get();
        if (name.isEmpty() || name.equals("0")) {
            // 旧版为站名与 "http://" . site_url；新版站点地址取 base-url（不含末尾的 /）
            name = site.name();
            String base = site.baseUrl().href();
            url = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }
        if (!url.startsWith("http")) {
            url = "http://" + url;
        }
        if (hasControl(name) || hasControl(url)) {
            return text(HttpStatus.BAD_REQUEST, "参数错误");
        }
        byte[] body = String.format(SHORTCUT_TEMPLATE, url).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(name + ".url"))
                .contentLength(body.length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /** 旧 Qrcode::index（Qrcode.php:14-24）：合法 URL 输出 PNG，否则空响应（200）。 */
    private ResponseEntity<byte[]> qrcode(LegacyParams p) {
        Optional<byte[]> png = QrcodeImages.png(p.raw("url"));
        if (png.isEmpty()) {
            return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .contentLength(0).cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff").body(new byte[0]);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(png.get().length)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(png.get());
    }

    /** RFC 6266 / RFC 5987：ASCII 兜底名（非 ASCII、引号、反斜线、控制字符换成 _）+ UTF-8 的 filename*。 */
    static String contentDisposition(String filename) {
        StringBuilder ascii = new StringBuilder();
        StringBuilder ext = new StringBuilder();
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            int u = b & 0xff;
            boolean attrChar = (u >= 'a' && u <= 'z') || (u >= 'A' && u <= 'Z') || (u >= '0' && u <= '9')
                    || "!#$&+-.^_`|~".indexOf(u) >= 0;
            ext.append(attrChar ? String.valueOf((char) u) : String.format("%%%02X", u));
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + ext;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .contentLength(bytes.length).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
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

    /** PATH_INFO 的百分号解码（{@code +} 不是空格），结果须是合法 UTF-8。 */
    static Optional<String> percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return Optional.of(s);
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '%') {
                if (i + 2 >= in.length || Character.digit(in[i + 1], 16) < 0 || Character.digit(in[i + 2], 16) < 0) {
                    return Optional.empty();
                }
                out.write(Character.digit(in[i + 1], 16) * 16 + Character.digit(in[i + 2], 16));
                i += 2;
            } else {
                out.write(in[i]);
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}

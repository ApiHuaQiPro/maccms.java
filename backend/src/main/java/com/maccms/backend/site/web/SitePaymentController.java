package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.member.application.MemberPaymentService;
import com.maccms.backend.member.application.view.PayCallback;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import com.maccms.backend.site.web.view.UserJumpPage;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 支付回调 {@code index.php/payment/notify/pay_type/<方式>}（旧 {@code index/controller/Payment.php}，B24 E05，33 文档第 7 节）。
 * 入站接口：支付平台 POST 通知（码支付、Zhapay、支付宝为表单，微信为 XML 正文），本系统不因此发出任何请求。
 * <ul>
 * <li>不是 POST（含支付宝、码支付、Zhapay 支付后浏览器回到 {@code return_url}）：ThinkPHP {@code success('支付完成！', url('user/index'))}；</li>
 * <li>POST：{@link MemberPaymentService#notify} 按旧版返回正文（{@code success}、{@code fail}、微信的 XML、"该支付选项未开启！"等），
 * {@code text/html; charset=utf-8}。</li>
 * </ul>
 * 地址形态同会员控制器：{@code /index.php/payment/<操作>[.html][/键/值…]}，{@code rewrite} 打开时另认去掉 {@code /index.php} 的形态。
 * 操作只有 {@code notify}，其他为 404。可写数据源关闭时 POST 返回 503（支付平台会按其策略重发）。
 */
@Controller
@ConditionalOnSiteEnabled
public class SitePaymentController {

    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(?:html|htm|shtm|shtml|xml)$");
    private static final Pattern PATH_PAIR = Pattern.compile("(\\w+)\\|([^|]+)");
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final SiteHtmlRenderer renderer;
    private final SitePages pages;
    private final ObjectProvider<MemberPaymentService> payments;

    public SitePaymentController(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer,
                                 ObjectProvider<MemberPaymentService> payments) {
        this.site = pages.site();
        this.pages = pages;
        this.urls = urls;
        this.renderer = renderer;
        this.payments = payments;
    }

    @RequestMapping(value = {"/index.php/payment", "/index.php/payment/**", "/payment", "/payment/**"},
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<?> handle(HttpServletRequest request) {
        Map<String, String> vars = route(request);
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        Map<String, PhpInput.Value> get = PhpInput.parseQuery(request.getQueryString());
        if (!post) {
            return paid(isAjax(request, PhpInput.merge(get, Map.of(), vars)));
        }
        MemberPaymentService service = payments.getIfAvailable();
        byte[] raw = body(request);
        if (service == null || !service.available()) {
            return text(HttpStatus.SERVICE_UNAVAILABLE, "fail");
        }
        String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase(java.util.Locale.ROOT);
        Map<String, PhpInput.Value> form = type.startsWith("application/x-www-form-urlencoded") ? PhpInput.parse(raw)
                : Map.of();
        return text(HttpStatus.OK, service.notify(new PayCallback(PhpInput.merge(get, form, vars), form, raw)));
    }

    /** 旧 {@code success(lang('index/payment_ok'), url('user/index'))}。 */
    private ResponseEntity<?> paid(boolean ajax) {
        String index = site.publicPath(urls.user("index")).href();
        if (ajax) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 1);
            body.put("msg", "支付完成！");
            body.put("data", "");
            body.put("url", index);
            body.put("wait", 3);
            return SiteUserController.jsonBody(body, List.of());
        }
        UserJumpPage page = new UserJumpPage(pages.member("操作成功"), true, SiteVariants.text("支付完成！"),
                Optional.of(site.publicPath(urls.user("index"))));
        return renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesUserJump(page));
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(SiteHtmlRenderer.HTML_UTF8).contentLength(bytes.length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex").body(bytes);
    }

    private static byte[] body(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ApiException(ErrorCode.INVALID_PARAMETER, "请求正文过大");
            }
            return bytes;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static boolean isAjax(HttpServletRequest request, Map<String, PhpInput.Value> params) {
        String h = request.getHeader("X-Requested-With");
        if (h != null && h.equalsIgnoreCase("XMLHttpRequest")) {
            return true;
        }
        PhpInput.Value v = params.get("_ajax");
        return v != null && (v.array() || !(v.bytes().length == 0 || v.bytes().length == 1 && v.bytes()[0] == '0'));
    }

    /** 路径 → 路由参数；操作不是 {@code notify} 时 404。 */
    private Map<String, String> route(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        String pathInfo;
        if (raw.equals("/index.php/payment") || raw.startsWith("/index.php/payment/")) {
            pathInfo = raw.substring("/index.php/".length());
        } else if (urls.style().rewrite()) {
            pathInfo = raw.substring(1);
        } else {
            throw notFound();
        }
        if (pathInfo.length() > 1024) {
            throw notFound();
        }
        String decoded = SiteAjaxController.percentDecode(pathInfo).orElseThrow(SitePaymentController::notFound);
        String p = SUFFIX.matcher(decoded).replaceFirst("").replaceAll("^/+|/+$", "");
        String[] seg = p.split("/", -1);
        String action = seg.length > 1 && !seg[1].isEmpty() ? seg[1] : "index";
        if (!action.equals("notify")) {
            throw notFound();
        }
        Map<String, String> vars = new LinkedHashMap<>();
        if (seg.length > 2) {
            List<String> rest = new ArrayList<>(List.of(seg).subList(2, seg.length));
            Matcher m = PATH_PAIR.matcher(String.join("|", rest));
            while (m.find()) {
                vars.put(m.group(1), m.group(2));
            }
        }
        return vars;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
    }
}

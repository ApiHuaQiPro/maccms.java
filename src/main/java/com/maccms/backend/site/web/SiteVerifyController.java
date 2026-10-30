package com.maccms.backend.site.web;

import com.maccms.backend.common.captcha.CaptchaSettings;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 旧 {@code index/controller/Verify.php} 的本地验证码（B08，17 文档第 2.4 节）。
 * <ul>
 * <li>{@code verify/index}：生成验证码 PNG，记录写入进程内会话（没有会话时新建并写出会话 cookie），覆盖同槽的旧记录——
 * 这就是"刷新验证码"：前端重新请求本地址（旧模板在地址后加随机数防缓存）即换一张新图，旧码随之失效。可选查询参数 {@code id}
 * 选择验证码槽（旧 {@code entry($id)}）；旧版所有校验点（包括 {@code verify/check}）都只校验默认槽，所以带 {@code id} 生成的码
 * 在前台无处可用，与旧版相同。</li>
 * <li>{@code verify/check}：旧 {@code Verify::check($verify, $id)}。旧代码接收 {@code id} 但调用 {@code captcha_check($verify)} 时
 * 不传它（{@code application/index/controller/Verify.php:21-29}），所以这里同样<b>忽略 {@code id}、只校验默认槽</b>，按
 * {@link Captchas#check} 校验并作废（通过或猜错都作废，之后须刷新），正文为 {@code 1} 或 {@code 0}（{@code text/html}，与旧版
 * ThinkPHP 默认输出类型相同）。参数取 GET 或表单 POST（同名多值取最后一个，与 PHP 相同）。</li>
 * </ul>
 * 两者都不缓存（no-store；旧版因开启会话由 PHP 发出 no-store/no-cache）。{@code ajax/verify_check}（通过后放行搜索/筛选页）不在本批，
 * 归 B11（15 文档第 7.4 节），所以 {@code show_verify}/{@code search_verify} 仍按 B05 的临时关闭处理。
 */
@Controller
@ConditionalOnSiteEnabled
public class SiteVerifyController {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{0,32}");
    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final SiteSessions sessions;
    private final CaptchaSettings settings;
    private final RandomGenerator random;

    /** @param random 验证码的随机源（生产为 {@code SecureRandom}，见 SiteConfiguration） */
    public SiteVerifyController(SiteSessions sessions, CaptchaSettings settings, RandomGenerator random) {
        this.sessions = sessions;
        this.settings = settings;
        this.random = random;
    }

    @GetMapping({"/index.php/verify/index", "/index.php/verify/index.html", "/index.php/verify/index/",
            "/verify/index", "/verify/index.html", "/verify/index/"})
    public ResponseEntity<byte[]> image(HttpServletRequest request, HttpServletResponse response) {
        String id = slot(request.getParameterValues("id"));
        SessionState session = sessions.currentOrCreate(request, response);
        byte[] png = Captchas.issue(session, id, settings, sessions.nowEpochSeconds(), random);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(png.length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(png);
    }

    @RequestMapping(value = {"/index.php/verify/check", "/index.php/verify/check.html", "/index.php/verify/check/",
            "/verify/check", "/verify/check.html", "/verify/check/"},
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<String> check(HttpServletRequest request) {
        String code = last(request.getParameterValues("verify"));
        boolean ok = Captchas.check(sessions.current(request), code, Captchas.DEFAULT_SLOT, sessions.nowEpochSeconds());
        String body = ok ? "1" : "0";
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .contentLength(1)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(body);
    }

    private static String slot(String[] values) {
        String id = last(values);
        if (id == null) {
            return "";
        }
        if (!ID.matcher(id).matches()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        return id;
    }

    private static String last(String[] values) {
        return values == null || values.length == 0 ? null : values[values.length - 1];
    }
}

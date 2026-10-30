package com.maccms.backend.install.web;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.install.application.InstallEnvironment;
import com.maccms.backend.install.application.InstallWizard;
import com.maccms.backend.install.web.view.IndexPage;
import com.maccms.backend.install.web.view.Step2Page;
import com.maccms.backend.install.web.view.Step3Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 安装向导入口（旧 {@code install.php} → {@code app\install\controller\Index::index}，B22 31 文档第 2～4 节）：
 * {@code /install.php}、{@code /install.php/index/index[.html]} 等，按参数 {@code step} 分派（旧 Index.php:26-77）。
 * <ul>
 * <li>已安装（安装锁存在）时任何请求只输出旧版的提示文字（install.php:30-33）；</li>
 * <li>会话（Servlet 会话）里保存旧版的 {@code lang}、{@code install_error}，以及新版的表单令牌；</li>
 * <li>参数按 PHP 的规则解析（{@link PhpInput}，查询串与表单分开，表单优先）；只取标量，数组按缺失处理。</li>
 * </ul>
 */
public class InstallEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(InstallEndpoint.class);

    /** 旧 install.php:31。 */
    static final String LOCKED_TEXT = "如需重新安装请删除【To re install, please remove】 >>> /application/data/install/install.lock";
    /** 安装入口的地址（旧版 ThinkPHP 绑定 install 模块后，{@code index/index} 及其省略写法，可带伪静态后缀）。 */
    public static final Pattern PATHS = Pattern.compile("/install\\.php(?:/|/index(?:/|/index(?:\\.(?:html|htm|shtml|shtm|xml))?/?)?)?");
    static final String INDEX_URL = "/install.php/index/index.html";
    static final List<String> LANGS = List.of("zh-cn", "zh-tw");
    static final int MAX_BODY = 1 << 20;

    static final String S_LANG = "install.lang";
    static final String S_ERROR = "install.install_error";
    static final String S_TOKEN = "install.token";

    private final InstallWizard wizard;
    private final InstallEnvironment environment;
    private final InstallResponses responses;
    private final String staticPath;
    private final String assetsPath;
    private final SecureRandom random = new SecureRandom();

    public InstallEndpoint(InstallWizard wizard, InstallEnvironment environment, InstallResponses responses, String staticPath,
                           String assetsPath) {
        this.wizard = wizard;
        this.environment = environment;
        this.responses = responses;
        this.staticPath = staticPath;
        this.assetsPath = assetsPath;
    }

    public ServerResponse handle(ServerRequest request) {
        try {
            return dispatch(request.servletRequest());
        } catch (RuntimeException | IOException e) {
            LOG.error("安装向导处理失败：{}", SafeExceptionLog.describe(e));
            return InstallResponses.html(HttpStatus.INTERNAL_SERVER_ERROR,
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>500</title></head><body>系统错误</body></html>");
        }
    }

    private ServerResponse dispatch(HttpServletRequest req) throws IOException {
        if (wizard.locked()) {
            return responses.text(LOCKED_TEXT);
        }
        Map<String, PhpInput.Value> get = PhpInput.parseQuery(req.getQueryString());
        Map<String, PhpInput.Value> post = "POST".equalsIgnoreCase(req.getMethod()) && isForm(req)
                ? PhpInput.parse(body(req)) : Map.of();
        Map<String, PhpInput.Value> param = PhpInput.merge(get, post, Map.of());
        boolean ajax = isAjax(req, param);
        HttpSession session = req.getSession(true);

        Object sessionLang = session.getAttribute(S_LANG);
        LegacyLang lang = sessionLang instanceof String s && LANGS.contains(s) ? LegacyLang.of(s) : LegacyLang.of(LegacyLang.DEFAULT_CODE);

        String step = scalar(param, "step");
        if (is(step, 2)) {
            session.setAttribute(S_ERROR, false);
            InstallEnvironment.Result env = environment.check(lang);
            if (env.failed()) {
                session.setAttribute(S_ERROR, true);
            }
            return responses.page(t -> t.installPagesStep2(new Step2Page(lang, staticPath, env)));
        }
        if (is(step, 3)) {
            if (Boolean.TRUE.equals(session.getAttribute(S_ERROR))) {
                return responses.error(ajax, lang.get("install/environment_failed"));
            }
            String token = token(session);
            return responses.page(t -> t.installPagesStep3(new Step3Page(lang, staticPath, assetsPath, "/", token)));
        }
        if (is(step, 4)) {
            if (Boolean.TRUE.equals(session.getAttribute(S_ERROR))) {
                return responses.error(ajax, lang.get("install/environment_failed"));
            }
            if (!"POST".equalsIgnoreCase(req.getMethod())) {
                return responses.error(ajax, lang.get("install/access_denied"));
            }
            Map<String, String> fields = new LinkedHashMap<>();
            post.forEach((k, v) -> {
                if (!v.array()) {
                    fields.put(k, v.text());
                }
            });
            InstallWizard.Reply r = wizard.testConnection(fields, tokenOk(session, post), lang);
            return reply(ajax, r);
        }
        if (is(step, 5)) {
            if (Boolean.TRUE.equals(session.getAttribute(S_ERROR))) {
                return responses.error(ajax, lang.get("install/init_err"));
            }
            byte[] pwd = PhpInput.scalar(post, "password");
            InstallWizard.Reply r = wizard.install(scalar(post, "account"),
                    pwd == null ? null : new String(pwd, StandardCharsets.UTF_8), pwd, scalar(post, "install_dir"),
                    scalar(post, "initdata"), tokenOk(session, post), lang);
            return reply(ajax, r);
        }
        // 默认：首页（旧 Index.php:62-75）——按参数 lang 选语言包并记进会话，同时清除环境检测的结果
        String chosen = scalar(param, "lang");
        if (chosen == null || !LANGS.contains(chosen)) {
            chosen = LegacyLang.DEFAULT_CODE;
        }
        session.setAttribute(S_LANG, chosen);
        session.setAttribute(S_ERROR, false);
        LegacyLang pageLang = LegacyLang.of(chosen);
        String selected = chosen;
        return responses.page(t -> t.installPagesIndex(new IndexPage(pageLang, staticPath, assetsPath, LANGS, selected, INDEX_URL)));
    }

    private ServerResponse reply(boolean ajax, InstallWizard.Reply r) {
        return r.success() ? responses.success(ajax, r.msg(), r.url() == null ? "" : r.url()) : responses.error(ajax, r.msg());
    }

    /** 旧 {@code switch ($step)} 的 {@code case N}：PHP 宽松比较（数字串按数值比较，其余不相等；数组、缺失为默认分支）。 */
    static boolean is(String step, int n) {
        if (step == null) {
            return false;
        }
        if (!Pattern.matches("[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?", step)) {
            return false;
        }
        try {
            return new BigDecimal(step.strip()).compareTo(BigDecimal.valueOf(n)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** ThinkPHP {@code Request::isAjax}：{@code X-Requested-With: XMLHttpRequest}（不区分大小写），或参数 {@code _ajax} 为真。 */
    static boolean isAjax(HttpServletRequest req, Map<String, PhpInput.Value> param) {
        String h = req.getHeader("X-Requested-With");
        if (h != null && h.toLowerCase(Locale.ROOT).equals("xmlhttprequest")) {
            return true;
        }
        String a = scalar(param, "_ajax");
        return a != null && !a.isEmpty() && !a.equals("0");
    }

    private String token(HttpSession session) {
        Object t = session.getAttribute(S_TOKEN);
        if (t instanceof String s) {
            return s;
        }
        byte[] b = new byte[16];
        random.nextBytes(b);
        String s = HexFormat.of().formatHex(b);
        session.setAttribute(S_TOKEN, s);
        return s;
    }

    private static boolean tokenOk(HttpSession session, Map<String, PhpInput.Value> post) {
        Object expected = session.getAttribute(S_TOKEN);
        byte[] got = PhpInput.scalar(post, InstallWizard.TOKEN_FIELD);
        return expected instanceof String s && got != null
                && MessageDigest.isEqual(s.getBytes(StandardCharsets.US_ASCII), got);
    }

    private static String scalar(Map<String, PhpInput.Value> params, String name) {
        byte[] v = PhpInput.scalar(params, name);
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    private static boolean isForm(HttpServletRequest req) {
        String ct = req.getContentType();
        return ct != null && ct.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private static byte[] body(HttpServletRequest req) throws IOException {
        try (InputStream in = req.getInputStream()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                throw new IOException("表单过大");
            }
            return b;
        }
    }
}

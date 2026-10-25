package com.maccms.backend.site.web;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteTemplateSelection;
import com.maccms.backend.site.web.view.SiteVariant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 前台每个请求的手机模板与语言选择（B12 承接 B05 D-B05-8，docs/architecture/21-b12-admin-framework.md 第 12 节）。
 *
 * <p>启动时读一次旧配置（与站点其他设置相同，改了要重启）：{@code extra/maccms.php} 的 {@code site} 与 {@code app.lang}、
 * {@code extra/domain.php}（没有这个文件时按没有站群）。{@link #filter()} 在每个请求开始时按 {@code User-Agent} 与 {@code Host} 原文算出
 * {@link SiteVariant}（{@link SiteTemplateSelection#resolve}）放进请求属性，之后页面外壳、错误页与提示经 {@link #current()} 取用——包括
 * 异常处理器输出的错误页（过滤器先于一切处理器执行）。请求之外（单元测试直接组装视图模型）为 {@link SiteVariant#DEFAULT}。
 */
public final class SiteVariants {

    /** 请求属性名。 */
    static final String ATTRIBUTE = SiteVariants.class.getName() + ".variant";

    private final PhpArray site;
    private final PhpValue domains;
    private final LegacyLang lang;
    private final boolean dependsOnUserAgent;
    private final Optional<SafeUrl> wapLogo;

    private SiteVariants(PhpArray site, PhpValue domains, LegacyLang lang, Optional<SafeUrl> wapLogo) {
        this.site = site;
        this.domains = domains;
        this.lang = lang;
        this.dependsOnUserAgent = site != null && SiteTemplateSelection.dependsOnUserAgent(site);
        this.wapLogo = wapLogo;
    }

    /** 没有旧配置目录：一律桌面、简体，与 B05～B11 相同。 */
    public static SiteVariants none() {
        return new SiteVariants(null, null, LegacyLang.of(LegacyLang.DEFAULT_CODE), Optional.empty());
    }

    /**
     * @param maccms  {@code extra/maccms.php}
     * @param domains {@code extra/domain.php}，没有这个文件时为 null
     * @throws IllegalStateException {@code site_waplogo} 不是空串、http/https 绝对地址或以单个 / 开头的站内路径（同 {@code site_logo}
     *                               的规则）；信息只含键名
     */
    public static SiteVariants from(PhpArray maccms, PhpArray domains) {
        // 没有 site 组（或不是数组）时按空组：旧版此时各项都是空值，不切手机模板
        PhpArray site = maccms.get("site") instanceof PhpArray s ? s : new PhpArray(Map.of());
        String waplogoRaw = PhpValues.text(site.get("site_waplogo")).trim();
        Optional<SafeUrl> wapLogo = waplogoRaw.isEmpty() ? Optional.empty() : SafeUrl.parse(waplogoRaw);
        if (!waplogoRaw.isEmpty() && wapLogo.isEmpty()) {
            throw new IllegalStateException("site 已启用但旧配置 extra/maccms.php 的 site.site_waplogo 不受支持"
                    + "（留空，或 http/https 绝对地址，或以单个 / 开头的站内路径）");
        }
        String configured = PhpValues.text(maccms.path("app", "lang"));
        LegacyLang.Selection selection = LegacyLang.select(configured);
        return new SiteVariants(site, domains, selection.pack(), wapLogo);
    }

    /** 选中的语言包（旧 {@code app.lang}，D-B12-L1：不受支持的值回退简体）。 */
    public LegacyLang lang() {
        return lang;
    }

    /** 主站配置的手机 Logo（旧 {@code site_waplogo}）；手机访问时页面外壳优先用它。 */
    public Optional<SafeUrl> wapLogo() {
        return wapLogo;
    }

    /** 页面输出是否随 {@code User-Agent} 不同（{@code mob_status} 为 1 或 2）：是则页面响应带 {@code Vary: User-Agent}。 */
    public boolean dependsOnUserAgent() {
        return dependsOnUserAgent;
    }

    /** 按请求头算出本次的选择。没有旧配置时为桌面、所选语言。 */
    public SiteVariant resolve(String userAgent, String host) {
        if (site == null) {
            return new SiteVariant(false, false, lang, "", "");
        }
        SiteTemplateSelection.Result r = SiteTemplateSelection.resolve(site, domains, userAgent, host == null ? "" : host);
        return new SiteVariant(r.wap(), r.domain(), lang, r.pathTpl(), r.pathAds());
    }

    /** 每个请求开始时算出选择并放进请求属性。 */
    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                request.setAttribute(ATTRIBUTE, resolve(request.getHeader("User-Agent"), request.getHeader("Host")));
                chain.doFilter(request, response);
            }

            @Override
            protected boolean shouldNotFilterErrorDispatch() {
                return true;
            }
        };
    }

    /** 当前请求的选择；请求之外，或请求没有经过 {@link #filter()} 时为 {@link SiteVariant#DEFAULT}。 */
    public static SiteVariant current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof SiteVariant v) {
            return v;
        }
        return SiteVariant.DEFAULT;
    }

    /** 旧版 {@code lang()} 输出的提示按当前请求所选语言包给出（{@link LegacyLang#localize}）。 */
    static String text(String zhCn) {
        return current().lang().localize(zhCn);
    }

    /** JSON 回复里的 {@code msg}（顶层 Map 的字符串值）按所选语言包给出；其他内容原样。 */
    static Object localizeMsg(Object body) {
        if (body instanceof Map<?, ?> m && m.get("msg") instanceof String msg) {
            String localized = text(msg);
            if (!localized.equals(msg)) {
                LinkedHashMap<Object, Object> copy = new LinkedHashMap<>(m);
                copy.put("msg", localized);
                return copy;
            }
        }
        return body;
    }
}

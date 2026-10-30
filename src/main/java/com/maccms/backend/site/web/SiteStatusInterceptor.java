package com.maccms.backend.site.web;

import com.maccms.backend.site.application.SiteSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 站点关闭：site 包内的页面 Controller 一律不执行（因此不查询数据库），直接抛 {@link SiteClosedException}，
 * 由 SiteExceptionHandler 输出 503 维护页。content API 等其他模块的路由不受影响。
 *
 * <p>与旧版差异：旧 PHP 在 index/Base 构造时输出关闭模板后 die，未设置状态码（application/index/controller/Base.php:73-79，
 * 代码阅读，未实测）；这里返回 503 + Retry-After + no-store + noindex。
 */
public class SiteStatusInterceptor implements HandlerInterceptor {

    static final String SITE_PACKAGE = "com.maccms.backend.site";

    private final SiteSettings settings;

    public SiteStatusInterceptor(SiteSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (settings.closed() && handler instanceof HandlerMethod method && isSiteHandler(method.getBeanType())) {
            throw new SiteClosedException();
        }
        return true;
    }

    static boolean isSiteHandler(Class<?> type) {
        String pkg = type.getPackageName();
        return pkg.equals(SITE_PACKAGE) || pkg.startsWith(SITE_PACKAGE + ".");
    }
}

package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * site 页面的异常处理：只作用于 site 包内的 Controller，优先于全局 JSON 处理（GlobalExceptionHandler）。
 * 一律输出完整的 HTML 错误页（noindex、no-store），不依赖 Boot 的 /error 转发；
 * 日志只用 SafeExceptionLog 记录类型与栈帧，不记录异常 message、请求头、cookie 或参数。
 */
@ControllerAdvice(basePackages = "com.maccms.backend.site")
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnSiteEnabled
public class SiteExceptionHandler {

    /** 维护关闭的建议重试间隔。 */
    static final Duration CLOSED_RETRY_AFTER = Duration.ofHours(1);
    /** 服务暂时不可用的建议重试间隔。 */
    static final Duration UNAVAILABLE_RETRY_AFTER = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(SiteExceptionHandler.class);

    private final SiteHtmlRenderer renderer;
    private final SitePages pages;

    public SiteExceptionHandler(SiteHtmlRenderer renderer, SitePages pages) {
        this.renderer = renderer;
        this.pages = pages;
    }

    /** 站点关闭：维护页，不查库。 */
    @ExceptionHandler(SiteClosedException.class)
    public ResponseEntity<String> closed(SiteClosedException e) {
        return renderer.error(HttpStatus.SERVICE_UNAVAILABLE, pages.closed(), CLOSED_RETRY_AFTER);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> api(ApiException e) {
        return switch (e.code()) {
            // 页面语义下参数不合法（越界页码、非法 id 等）等同于资源不存在（04 报告第 5.3 节）
            case INVALID_PARAMETER, CATEGORY_NOT_FOUND, VIDEO_NOT_FOUND, NOT_FOUND, METHOD_NOT_ALLOWED -> notFound();
            case SERVICE_UNAVAILABLE -> {
                log.warn("site page unavailable: {}", SafeExceptionLog.typeChain(e));
                yield unavailable();
            }
            // 观看密码、版权：按 03 方案第 4.2 节应交还 PHP，交还机制需与部署一起评审，骨架未实现。
            // 先返回 503（no-store、noindex），不渲染详情、不泄露原因；实现三页时替换为交还响应。
            case VIDEO_PASSWORD_REQUIRED, VIDEO_COPYRIGHT_RESTRICTED -> {
                log.warn("site handoff not implemented: {}", e.code());
                yield unavailable();
            }
            case INTERNAL_ERROR -> serverError(e);
        };
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return notFound();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> other(Exception e) {
        return serverError(e);
    }

    private ResponseEntity<String> notFound() {
        return renderer.error(HttpStatus.NOT_FOUND, pages.notFound(), null);
    }

    private ResponseEntity<String> unavailable() {
        return renderer.error(HttpStatus.SERVICE_UNAVAILABLE, pages.unavailable(), UNAVAILABLE_RETRY_AFTER);
    }

    private ResponseEntity<String> serverError(Exception e) {
        log.error("site page failed: {}", SafeExceptionLog.describe(e));
        return renderer.error(HttpStatus.INTERNAL_SERVER_ERROR, pages.serverError(), null);
    }
}

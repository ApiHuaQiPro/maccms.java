package com.maccms.backend.admin.web;

import com.maccms.backend.admin.config.ConditionalOnAdminEnabled;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.SafeExceptionLog;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 后台请求中的异常（优先于全局的 JSON 处理）：输出固定文字的错误页，不含异常信息；日志只记类型链（02 规范第 4 节）。
 * 数据库不可用等异常也走这里（旧版是 ThinkPHP 的 System Error 页）。
 */
@ControllerAdvice(assignableTypes = AdminController.class)
@ConditionalOnAdminEnabled
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    static final String ERROR_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="utf-8"><meta name="robots" content="noindex"><title>系统错误</title></head>
            <body><h1>页面错误！请稍后再试～</h1></body>
            </html>
            """;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> api(ApiException e) {
        HttpStatus status = e.code().status();
        return page(status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> any(Exception e) {
        log.error("admin request failed: {}", SafeExceptionLog.typeChain(e));
        return page(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<String> page(HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(AdminResponses.HTML_UTF8)
                .contentLength(ERROR_HTML.getBytes(StandardCharsets.UTF_8).length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .header("Content-Security-Policy", AdminResponses.CONTENT_SECURITY_POLICY)
                .body(ERROR_HTML);
    }
}

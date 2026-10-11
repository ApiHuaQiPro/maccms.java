package com.maccms.backend.site.web;

/** 页面渲染失败。message 固定，不携带被包装异常的 message；日志只记录类型链。 */
public class PageRenderException extends RuntimeException {

    public PageRenderException(Throwable cause) {
        super("page render failed", cause);
    }
}

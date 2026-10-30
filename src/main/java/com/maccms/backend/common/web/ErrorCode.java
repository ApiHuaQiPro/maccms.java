package com.maccms.backend.common.web;

import org.springframework.http.HttpStatus;

/**
 * 对外错误码。前后端契约的一部分：新增或修改必须同步 docs/contracts/*.openapi.yaml。
 */
public enum ErrorCode {
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND),
    VIDEO_PASSWORD_REQUIRED(HttpStatus.FORBIDDEN),
    VIDEO_COPYRIGHT_RESTRICTED(HttpStatus.FORBIDDEN),
    ARTICLE_NOT_FOUND(HttpStatus.NOT_FOUND),
    ARTICLE_PASSWORD_REQUIRED(HttpStatus.FORBIDDEN),
    ARTICLE_POINTS_REQUIRED(HttpStatus.FORBIDDEN),
    TOPIC_NOT_FOUND(HttpStatus.NOT_FOUND),
    ACTOR_NOT_FOUND(HttpStatus.NOT_FOUND),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND),
    WEBSITE_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}

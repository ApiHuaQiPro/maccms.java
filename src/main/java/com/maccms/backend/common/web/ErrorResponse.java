package com.maccms.backend.common.web;

import java.util.List;

/** 统一错误响应体，见 OpenAPI components.schemas.ErrorResponse。 */
public record ErrorResponse(String code, String message, List<ApiException.FieldError> fieldErrors) {

    public static ErrorResponse of(ApiException e) {
        return new ErrorResponse(e.code().name(), e.getMessage(), e.fieldErrors());
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, List.of());
    }
}

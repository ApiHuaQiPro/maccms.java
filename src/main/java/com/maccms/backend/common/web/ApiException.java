package com.maccms.backend.common.web;

import java.util.List;

/**
 * 业务层抛出的、需要映射成固定错误响应的异常。message 会原样返回给调用方，不能包含内部细节。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldError> fieldErrors;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static ApiException invalidParameter(String field, String message) {
        return new ApiException(ErrorCode.INVALID_PARAMETER, "请求参数不合法",
                List.of(new FieldError(field, message)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public record FieldError(String field, String message) {
    }
}

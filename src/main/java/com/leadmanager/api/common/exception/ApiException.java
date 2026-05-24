package com.leadmanager.api.common.exception;

/**
 * Base class for all checked business errors thrown by the service layer.
 * <p>
 * Why an explicit base type rather than throwing {@link RuntimeException} subclasses
 * directly: the {@link com.leadmanager.api.common.exception.GlobalExceptionHandler}
 * pattern-matches on this type to map an {@link ErrorCode} to an RFC 7807 response
 * without inspecting messages or relying on reflection. Concrete subclasses
 * (e.g. {@code BlindTransferViolationException}) carry domain context; the handler
 * does not need to know about each one.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

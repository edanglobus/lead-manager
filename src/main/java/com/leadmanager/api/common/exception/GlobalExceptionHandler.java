package com.leadmanager.api.common.exception;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.validation.ConstraintViolationException;

/**
 * Single point of translation from thrown exceptions to RFC 7807 {@link ProblemDetail}
 * responses (content type {@code application/problem+json}).
 * <p>
 * Why this lives in one place: per {@code CLAUDE.md} we MUST never leak stack traces
 * to clients, and every error must follow the same JSON contract. A central advice
 * lets the controllers throw freely without worrying about the wire format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, WebRequest request) {
        ErrorCode code = ex.errorCode();
        log.warn("Business error [{}] at {}: {}", code.name(), path(request), ex.getMessage());
        return ResponseEntity
                .status(code.status())
                .body(problem(code, ex.getMessage(), request, Map.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();

        log.warn("Validation error at {}: {} field(s) invalid", path(request), fieldErrors.size());

        return ResponseEntity
                .status(ErrorCode.VALIDATION_FAILED.status())
                .body(problem(
                        ErrorCode.VALIDATION_FAILED,
                        "Request payload failed validation",
                        request,
                        Map.of("errors", fieldErrors)));
    }

    /**
     * A required query param / path var was absent. Without this override
     * {@link ResponseEntityExceptionHandler} returns an empty 400 body,
     * breaking the RFC 7807 contract every other 4xx response honours.
     */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldError = Map.of(
                "field", ex.getParameterName(),
                "message", ex.getParameterName() + " is required");

        log.warn("Missing parameter at {}: {}", path(request), ex.getParameterName());

        return ResponseEntity
                .status(ErrorCode.VALIDATION_FAILED.status())
                .body(problem(
                        ErrorCode.VALIDATION_FAILED,
                        "Required parameter is missing",
                        request,
                        Map.of("errors", List.of(fieldError))));
    }

    /**
     * A query param / path var could not be coerced to its target type
     * (e.g. {@code categoryId=abc} where the controller declares {@code Long}).
     * Same rationale as {@link #handleMissingServletRequestParameter}: keep
     * the wire contract uniform.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {

        Map<String, String> fieldError = Map.of(
                "field", ex.getName(),
                "message", ex.getName() + " has an invalid value");

        log.warn("Type mismatch at {}: {} = {}", path(request), ex.getName(), ex.getValue());

        return ResponseEntity
                .status(ErrorCode.VALIDATION_FAILED.status())
                .body(problem(
                        ErrorCode.VALIDATION_FAILED,
                        "Request parameter has an invalid value",
                        request,
                        Map.of("errors", List.of(fieldError))));
    }

    /**
     * Bean Validation failures on individual method parameters (query
     * params, path variables) — distinct from {@link MethodArgumentNotValidException}
     * which only fires for {@code @Valid @RequestBody} payloads. Without
     * this handler, a 400-worthy "lat must be <= 90" would fall through
     * to {@link #handleUnexpected(Exception, WebRequest) handleUnexpected}
     * and surface as an opaque 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, WebRequest request) {

        List<Map<String, String>> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> Map.of(
                        "field", lastNode(v.getPropertyPath().toString()),
                        "message", v.getMessage()))
                .toList();

        log.warn("Constraint violation at {}: {} field(s) invalid", path(request), fieldErrors.size());

        return ResponseEntity
                .status(ErrorCode.VALIDATION_FAILED.status())
                .body(problem(
                        ErrorCode.VALIDATION_FAILED,
                        "Request parameters failed validation",
                        request,
                        Map.of("errors", fieldErrors)));
    }

    /**
     * Last-resort handler. Anything not matched above is logged at ERROR with the
     * full stack trace (for ops) but the wire response carries only a generic
     * message — never the underlying exception detail.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception at {}", path(request), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(
                        ErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred. Please try again later.",
                        request,
                        Map.of()));
    }

    private ProblemDetail problem(ErrorCode code, String detail, WebRequest request, Map<String, Object> extras) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());
        pd.setInstance(URI.create(path(request)));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", code.name());
        properties.putAll(extras);
        properties.forEach(pd::setProperty);
        return pd;
    }

    private static String path(WebRequest request) {
        String desc = request.getDescription(false);
        return desc != null && desc.startsWith("uri=") ? desc.substring(4) : desc;
    }

    /**
     * Trims a {@code jakarta.validation} property path like
     * {@code findNearby.query.latitude} down to the last segment
     * ({@code latitude}) so the wire-level {@code field} value matches the
     * shape produced by {@link #handleMethodArgumentNotValid} for body validation.
     */
    private static String lastNode(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot < 0 ? propertyPath : propertyPath.substring(lastDot + 1);
    }
}

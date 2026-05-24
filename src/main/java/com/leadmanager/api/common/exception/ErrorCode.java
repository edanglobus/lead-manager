package com.leadmanager.api.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Catalog of every error condition the API can return.
 * <p>
 * Each value carries:
 * <ul>
 *   <li>{@code type} – a stable, dereferenceable URI used as the RFC 7807 {@code type} field.
 *       Clients pattern-match on this URI, NOT on human-readable messages.</li>
 *   <li>{@code status} – the HTTP status the error maps to.</li>
 *   <li>{@code title} – short, generic, human-readable summary (no PII, no specifics).</li>
 * </ul>
 * <p>
 * Adding a new error is intentionally a code change — keeps the public error
 * contract auditable in version control.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "resource-not-found", "Resource not found"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "email-taken", "Email already registered"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error");

    private static final String TYPE_BASE = "https://leadmanager.com/errors/";

    private final HttpStatus status;
    private final String slug;
    private final String title;

    ErrorCode(HttpStatus status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return TYPE_BASE + slug;
    }
}

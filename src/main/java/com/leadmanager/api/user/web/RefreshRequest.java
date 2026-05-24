package com.leadmanager.api.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST /api/v1/auth/refresh}.
 * <p>
 * The plaintext field is intentionally named {@code refreshToken} (camelCase)
 * to match the response field, so a client can round-trip the value
 * without case juggling. Validation here is minimal — the service does
 * the real "is this token active" check.
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken is required")
        @Size(max = 256, message = "refreshToken too long")
        String refreshToken
) {
}

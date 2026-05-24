package com.leadmanager.api.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST /api/v1/auth/logout}.
 * <p>
 * Shape mirrors {@link RefreshRequest} so a client can reuse the same
 * field plumbing for both operations. The service treats the token
 * opaquely — validation here is purely "is something present".
 */
public record LogoutRequest(

        @NotBlank(message = "refreshToken is required")
        @Size(max = 256, message = "refreshToken too long")
        String refreshToken
) {
}

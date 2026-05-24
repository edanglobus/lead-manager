package com.leadmanager.api.user.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST /api/v1/auth/login}.
 * <p>
 * Validation here is intentionally lenient compared to {@code RegisterUserRequest}:
 * the login form's job is to surface "wrong credentials" via the service,
 * not to rule out malformed emails before they even reach the database.
 * Still: blank/missing inputs are blocked at the boundary so they don't
 * burn a bcrypt round in the service.
 */
public record LoginRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email address")
        @Size(max = 254, message = "email too long")
        String email,

        @NotBlank(message = "password is required")
        @Size(max = 72, message = "password too long")
        String password
) {
}

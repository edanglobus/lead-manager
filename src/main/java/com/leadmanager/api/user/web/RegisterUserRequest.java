package com.leadmanager.api.user.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST /api/v1/auth/register}.
 * <p>
 * This is the ONLY layer that does shape / format validation: Bean Validation
 * annotations are evaluated by Spring before the controller method body runs.
 * If any constraint fails, {@code GlobalExceptionHandler} translates the
 * resulting {@code MethodArgumentNotValidException} into an RFC 7807
 * response — the service never sees the bad input.
 * <p>
 * Why this is a separate type from {@link com.leadmanager.api.user.RegisterUserCommand}:
 * the request DTO is an HTTP concern (JSON shape, validation messages); the
 * command is a domain concern (immutable input port into the service). Keeping
 * them separate means the service can be invoked from non-HTTP callers (tests,
 * future CLIs, batch jobs) without dragging Jackson or Bean Validation into
 * the domain.
 *
 * @param email       provider's email; must be a syntactically valid address
 * @param password    plaintext password; bcrypt hashes accept up to 72 bytes,
 *                    so the upper bound is a hard cap, not a UX number
 * @param displayName public name shown on jobs and transfers
 * @param phone       optional phone in E.164-ish form; nullable
 */
public record RegisterUserRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email address")
        @Size(max = 254, message = "email too long")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 12, max = 72, message = "password must be 12–72 characters")
        String password,

        @NotBlank(message = "displayName is required")
        @Size(min = 1, max = 120, message = "displayName must be 1–120 characters")
        String displayName,

        @Pattern(
                regexp = "^\\+?[0-9 \\-]{6,32}$",
                message = "phone must be 6–32 chars of digits, spaces, hyphens, optionally leading +"
        )
        String phone
) {
}

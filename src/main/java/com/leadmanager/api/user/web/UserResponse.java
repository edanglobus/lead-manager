package com.leadmanager.api.user.web;

import java.time.Instant;

/**
 * Public JSON view of a {@link com.leadmanager.api.user.User}.
 * <p>
 * The set of fields here is the API contract. Adding a field is a
 * non-breaking change; removing or renaming one is a breaking change and
 * MUST coincide with bumping {@code ApiVersion}.
 * <p>
 * Deliberately omitted:
 * <ul>
 *   <li>{@code passwordHash} — never leaves the server, period.</li>
 *   <li>{@code version} — JPA optimistic-lock counter, an implementation
 *       detail. If we later need it for ETag headers we'll expose it
 *       explicitly, not by accident.</li>
 * </ul>
 *
 * @param id          server-assigned user id
 * @param email       email exactly as the user typed it (case preserved)
 * @param displayName public-facing name
 * @param phone       contact phone, or {@code null} if not provided
 * @param createdAt   when the account was registered, UTC
 */
public record UserResponse(
        Long id,
        String email,
        String displayName,
        String phone,
        Instant createdAt
) {
}

package com.leadmanager.api.user;

/**
 * Immutable input port for {@link UserService#register(RegisterUserCommand)}.
 * <p>
 * Why a record, not a DTO:
 * <ul>
 *   <li><b>SoC.</b> DTOs are an HTTP-layer concern (Bean Validation, JSON shape).
 *       Commands are domain input — the service must be callable from any
 *       source (a future admin CLI, a test, a message consumer) without
 *       dragging Jackson or {@code jakarta.validation} into the service tier.</li>
 *   <li><b>Immutability.</b> A record gives free {@code equals}/{@code hashCode}
 *       and prevents the service from mutating its inputs.</li>
 * </ul>
 * Format validation (email regex, password length) lives in the HTTP DTO.
 * The service only enforces business invariants (email uniqueness, etc.)
 * and trusts the caller for syntactic well-formedness.
 *
 * @param email       the user's email; stored as-typed but uniqueness is
 *                    enforced case-insensitively at the repository layer
 * @param rawPassword the plaintext password; the service hashes it before
 *                    persisting and never logs it
 * @param displayName public-facing name shown on jobs and transfers
 * @param phone       optional contact phone (nullable)
 */
public record RegisterUserCommand(
        String email,
        String rawPassword,
        String displayName,
        String phone
) {
}

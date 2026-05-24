package com.leadmanager.api.user;

/**
 * Immutable input port for {@link AuthService#login(LoginCommand)}.
 * <p>
 * Distinct from the HTTP {@code LoginRequest} for the same reasons
 * {@link RegisterUserCommand} is distinct from the register DTO: the
 * service tier must be callable from non-HTTP sources without dragging
 * Jackson or Bean Validation into the domain.
 *
 * @param email       email as the caller typed it; the service strips
 *                    surrounding whitespace and looks the user up
 *                    case-insensitively
 * @param rawPassword the plaintext password to verify against the
 *                    stored bcrypt hash
 */
public record LoginCommand(String email, String rawPassword) {
}

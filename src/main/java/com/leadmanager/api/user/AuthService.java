package com.leadmanager.api.user;

import com.leadmanager.api.common.security.JwtProperties;

/**
 * Application service for authentication flows: login, refresh, logout.
 * <p>
 * Why a separate interface from {@link UserService}: authentication is a
 * different concern from user CRUD. Splitting them keeps each service's
 * contract focused and lets the {@code AuthController} depend only on the
 * tier it needs.
 * <p>
 * Methods that reference {@link JwtProperties} in their JavaDoc do so for
 * context only; the implementation handles the wiring.
 */
public interface AuthService {

    /**
     * Verify credentials and mint a fresh (access, refresh) pair.
     * <p>
     * Invariants:
     * <ul>
     *   <li>Email is stripped of surrounding whitespace; lookup is
     *       case-insensitive via {@code UserRepository.findByEmailIgnoreCase}.</li>
     *   <li>Password verification uses {@code PasswordEncoder.matches},
     *       which runs bcrypt's intentionally slow check (mitigates
     *       brute force).</li>
     *   <li>"No such user" and "wrong password" surface as the SAME
     *       {@code ErrorCode.INVALID_CREDENTIALS} — preventing an attacker
     *       from probing which emails are registered.</li>
     * </ul>
     *
     * @throws com.leadmanager.api.common.exception.ApiException
     *         {@code INVALID_CREDENTIALS} if the email is unknown or the
     *         password does not match
     */
    LoginResult login(LoginCommand command);

    /**
     * Rotate a refresh token into a fresh (access, refresh) pair. The old
     * refresh token is revoked atomically and linked to the new one via
     * {@code replaced_by_id} for audit. Subsequent calls with the old
     * refresh token will fail.
     *
     * @param refreshTokenPlaintext the opaque plaintext from a prior
     *                              {@code /auth/login} or {@code /auth/refresh}
     *                              response
     * @throws com.leadmanager.api.common.exception.ApiException
     *         {@code INVALID_REFRESH_TOKEN} if the token is unknown,
     *         expired, or already revoked
     */
    LoginResult refresh(String refreshTokenPlaintext);

    /**
     * Revoke every active refresh token belonging to the user that owns
     * the supplied refresh token. Idempotent: an unknown or already-
     * revoked plaintext is a no-op — the desired state ("the holder is
     * logged out") is already true. Returning anything other than success
     * for invalid tokens would let an attacker enumerate which tokens
     * are still active by comparing responses.
     * <p>
     * Note: access tokens already issued remain valid until their {@code exp}
     * elapses (max {@link JwtProperties#accessTokenTtl()}). Server-side
     * revocation of access tokens would require a denylist, which is a
     * separate (later) feature.
     */
    void logout(String refreshTokenPlaintext);
}

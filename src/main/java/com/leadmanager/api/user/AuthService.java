package com.leadmanager.api.user;

/**
 * Application service for authentication flows (login today; refresh and
 * logout in sub-step 1.5).
 * <p>
 * Why a separate interface from {@link UserService}: authentication is a
 * different concern from user CRUD. Splitting them keeps each service's
 * contract focused and lets the {@code AuthController} depend only on the
 * tier it needs.
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
}

package com.leadmanager.api.user;

/**
 * Output port from {@link AuthService#login(LoginCommand)} and
 * {@link AuthService#refresh(String)}.
 * <p>
 * The controller maps this to {@code LoginResponse} for the wire; keeping
 * a domain-side type means future callers (e.g. an admin CLI) can reuse
 * the result shape without depending on HTTP.
 *
 * @param accessToken      compact JWS, ready for an
 *                         {@code Authorization: Bearer …} header
 * @param expiresInSeconds wall-clock seconds until the access token's
 *                         {@code exp} claim; mirrors the OAuth2 convention
 *                         so clients schedule refresh proactively
 * @param refreshToken     opaque plaintext to send to {@code POST /auth/refresh}
 *                         when the access token nears expiry. The server
 *                         only stored its SHA-256 hash — this is the only
 *                         time the plaintext ever leaves the server for
 *                         this particular rotation step
 * @param userId           id of the authenticated user
 */
public record LoginResult(
        String accessToken,
        long expiresInSeconds,
        String refreshToken,
        Long userId
) {
}

package com.leadmanager.api.user;

/**
 * Output port from {@link AuthService#login(LoginCommand)}.
 * <p>
 * The controller maps this to {@code LoginResponse} for the wire; keeping
 * a domain-side type means future callers (e.g. an admin CLI) can reuse
 * the result shape without depending on HTTP.
 *
 * @param accessToken      compact JWS, ready to be put into an
 *                         {@code Authorization: Bearer …} header
 * @param expiresInSeconds wall-clock seconds until the token's {@code exp}
 *                         claim; mirrors the OAuth2 convention so mobile
 *                         clients can schedule refresh proactively
 * @param userId           id of the authenticated user; convenient when
 *                         the client wants to skip a /me round-trip
 */
public record LoginResult(String accessToken, long expiresInSeconds, Long userId) {
}

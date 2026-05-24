package com.leadmanager.api.user;

import java.util.Optional;

/**
 * Low-level service for the refresh-token aggregate.
 * <p>
 * Responsibilities kept narrow on purpose: token generation, hashing,
 * lookup, rotation linking, and bulk revocation. Higher-level orchestration
 * (call {@code issue} during {@code AuthService.login}, swap old↔new during
 * {@code AuthService.refresh}, etc.) lives in {@link AuthService}.
 */
public interface RefreshTokenService {

    /** Mint a fresh refresh token for a user, persisting only its hash. */
    Issued issue(Long userId);

    /**
     * Look up the active row for a presented plaintext token. Returns
     * {@link Optional#empty()} when the token is unknown, expired, or
     * revoked — callers should treat all three as "invalid refresh".
     */
    Optional<RefreshToken> findActive(String plaintext);

    /** Mark {@code oldToken} revoked and link its rotation chain to {@code newToken}. */
    void replace(RefreshToken oldToken, RefreshToken newToken);

    /** Revoke every active refresh token for a user. Returns the number of rows touched. */
    int revokeAllActiveForUser(Long userId);

    /**
     * The result of {@link #issue(Long)}: the plaintext (which leaves the
     * server exactly once, in the HTTP response) and the persisted entity
     * (needed by {@link #replace(RefreshToken, RefreshToken)} during
     * rotation).
     */
    record Issued(String plaintext, RefreshToken entity) {
    }
}

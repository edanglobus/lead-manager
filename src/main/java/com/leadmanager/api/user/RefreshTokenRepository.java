package com.leadmanager.api.user;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence port for {@link RefreshToken}.
 * <p>
 * Lookups go through the {@code token_hash} unique index. The
 * {@link #revokeAllActiveForUser} update is a single statement that walks
 * the partial index {@code refresh_tokens_active_by_user_ix} — fast and
 * race-free relative to logout.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Bulk-revoke every active token for a user. Returns the number of rows
     * touched (zero is valid — the user simply had no active sessions).
     * {@code @Modifying} tells Spring Data this query mutates state, so it
     * needs to run inside a write transaction.
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken r
            SET    r.revokedAt = :now
            WHERE  r.userId = :userId
              AND  r.revokedAt IS NULL
            """)
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("now") Instant now);
}

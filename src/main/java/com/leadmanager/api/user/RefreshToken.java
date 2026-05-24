package com.leadmanager.api.user;

import java.time.Instant;

import com.leadmanager.api.common.audit.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One persisted refresh-token grant.
 * <p>
 * The plaintext token returned to the client is never stored. The row only
 * holds {@code token_hash} (SHA-256 hex of the plaintext) so a database
 * leak does not directly compromise active sessions.
 * <p>
 * Lifecycle: every fresh login or rotation creates a new row in
 * non-revoked, non-expired state. Calling {@code /auth/refresh} consumes
 * the current row (sets {@code revokedAt}, links {@code replacedById}) and
 * issues a new one. Calling {@code /auth/logout} marks every non-revoked
 * row for the user as revoked.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Setter
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Setter
    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Builder
    private RefreshToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** A row is active iff it has not been revoked and has not expired. */
    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** Idempotent: revoking an already-revoked token is a no-op. */
    public void revoke(Instant when) {
        if (this.revokedAt == null) {
            this.revokedAt = when;
        }
    }
}

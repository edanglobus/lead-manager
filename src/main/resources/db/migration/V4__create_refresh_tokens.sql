-- =====================================================================
-- V4__create_refresh_tokens.sql
-- Persistent storage for the long-lived half of the JWT auth pair.
--
-- Notes:
--   * The plaintext refresh token is NEVER stored. Only its SHA-256 hash
--     (64 hex chars) is. A leak of this table therefore does not let an
--     attacker forge refresh tokens — they would still need to pre-image
--     the hash to forge a usable plaintext.
--   * expires_at and revoked_at are TIMESTAMPTZ to match BaseEntity's
--     audit timestamps (Instant). A row is "active" iff
--     revoked_at IS NULL AND expires_at > now().
--   * replaced_by_id forms the rotation chain: when the holder of a
--     refresh token calls /auth/refresh, the old row is marked
--     revoked_at = now() and replaced_by_id = <new row's id>. Useful
--     for audit and for future re-use detection (v2: if a refresh
--     token is presented twice we know the family is compromised and
--     we revoke the entire user's chain).
--   * The partial index on (user_id, expires_at) WHERE revoked_at IS NULL
--     keeps logout O(active-tokens-per-user) without scanning history.
-- =====================================================================

CREATE TABLE refresh_tokens (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens(id),
    CONSTRAINT refresh_tokens_hash_length CHECK (length(token_hash) = 64)
);

CREATE UNIQUE INDEX refresh_tokens_token_hash_uq
    ON refresh_tokens (token_hash);

CREATE INDEX refresh_tokens_active_by_user_ix
    ON refresh_tokens (user_id, expires_at)
    WHERE revoked_at IS NULL;

-- =====================================================================
-- V3__create_users.sql
-- First domain table: provider identity + credentials.
--
-- Notes:
--   * email is stored as VARCHAR (preserves the case the user typed),
--     but uniqueness is enforced on LOWER(email) via a functional unique
--     index. Lookups MUST use LOWER(email) to hit the index.
--   * password_hash holds a bcrypt digest (60 chars). VARCHAR(72) leaves
--     headroom for the bcrypt $2b$/$2a$ prefix variants.
--   * version is the JPA optimistic-lock counter (@Version).
--   * created_at / updated_at are populated by Spring Data JPA Auditing;
--     DEFAULT now() is a safety net for any direct SQL inserts (e.g.
--     migrations or tests).
-- =====================================================================

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254)  NOT NULL,
    password_hash VARCHAR(72)   NOT NULL,
    display_name  VARCHAR(120)  NOT NULL,
    phone         VARCHAR(32),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT users_email_not_blank      CHECK (length(btrim(email)) > 0),
    CONSTRAINT users_display_name_not_blank CHECK (length(btrim(display_name)) > 0)
);

-- Case-insensitive uniqueness: prevents "Foo@bar.com" and "foo@bar.com"
-- from coexisting. Also serves as the primary lookup index for login.
CREATE UNIQUE INDEX users_email_lower_uq ON users (LOWER(email));

-- =====================================================================
-- V8__create_jobs.sql
-- The heart of the marketplace: the Job (a lead) plus its append-only
-- state-transition audit. A Job moves through a 9-state lifecycle from
-- creation to either CLOSED_PAID or one of the terminal failure states
-- (CANCELLED, EXPIRED). Every transition is recorded.
--
-- Notes:
--   * Customer data is EMBEDDED on the row, not extracted to a
--     customer table. Customers do not log in and have no per-row
--     lifecycle of their own. Blind-transfer masking will be enforced
--     at the DTO mapper layer (slice 5), NOT by hiding columns on
--     this table — both providers in a transfer chain need access to
--     the same row, just with different field-level redaction policy.
--   * customer_location is geography(Point, 4326) so future
--     "leads near me" queries can ST_DWithin against the GIST index.
--     PostGIS extension was enabled in V5.
--   * state is VARCHAR(32) + CHECK constraint rather than a Postgres
--     ENUM type. ENUMs require ALTER TYPE migrations to add a value,
--     which is painful under concurrent reader load; VARCHAR+CHECK
--     lets us evolve the state set with a normal column-rewrite-free
--     migration. The application Job.State enum is the source of truth.
--   * price_cents is BIGINT (integer cents per CLAUDE.md). Currency is
--     ISO 4217 alpha-3. v1 launches USD-only but the column is here so
--     a later locale slice doesn't need to widen the schema. No
--     floats / doubles for money anywhere.
--   * current_assignee_user_id is DENORMALIZED — the canonical chain
--     lives in transfers (slice 5) and the canonical state history
--     lives in job_state_transitions below. This column exists so
--     "show me jobs assigned to me right now" stays a single-index
--     lookup instead of a join through the transfer chain.
--   * jobs.assignee FK is ON DELETE RESTRICT so a user can't be hard-
--     deleted while they're holding an active assignment. originator
--     and category FKs are RESTRICT for the same "no dangling history"
--     reason. job_state_transitions FK to jobs is CASCADE because the
--     audit trail has no meaning without the job it audits.
--
-- Indexes:
--   * jobs_originator_state_ix   — "my jobs" sorted by state.
--   * jobs_assignee_active_ix    — partial index, only rows that have
--                                  an assignee. Powers the assignee
--                                  dashboard query.
--   * jobs_state_category_ix     — feed query: "OPEN jobs in trade X".
--   * jobs_customer_location_gix — spatial for nearby-leads search.
--   * jst_job_occurred_ix        — fetch a job's history newest-first.
-- =====================================================================

CREATE TABLE jobs (
    id                         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Ownership / chain participants
    originator_user_id         BIGINT       NOT NULL,
    current_assignee_user_id   BIGINT,

    -- Categorization & lifecycle
    service_category_id        BIGINT       NOT NULL,
    state                      VARCHAR(32)  NOT NULL,

    -- The lead description (provider-authored)
    title                      VARCHAR(120) NOT NULL,
    description                TEXT         NOT NULL,

    -- Embedded customer block (no Customer table — see header notes)
    customer_name              VARCHAR(120) NOT NULL,
    customer_phone             VARCHAR(32)  NOT NULL,
    customer_address           VARCHAR(255) NOT NULL,
    customer_location          geography(Point, 4326) NOT NULL,

    -- Money (integer cents, ISO 4217 currency)
    price_cents                BIGINT       NOT NULL,
    currency                   VARCHAR(3)   NOT NULL DEFAULT 'USD',

    -- Optional scheduled time (used by OPEN_TODAY, ASSIGNED, etc.)
    scheduled_for              TIMESTAMPTZ,

    -- Audit
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                    BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_jobs_originator
        FOREIGN KEY (originator_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_jobs_assignee
        FOREIGN KEY (current_assignee_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_jobs_category
        FOREIGN KEY (service_category_id) REFERENCES service_categories(id) ON DELETE RESTRICT,

    CONSTRAINT jobs_state_valid CHECK (state IN (
        'OPEN_GENERAL',
        'OPEN_TODAY',
        'PENDING_TRANSFER',
        'ASSIGNED',
        'IN_PROGRESS',
        'COMPLETED',
        'CLOSED_PAID',
        'CANCELLED',
        'EXPIRED'
    )),

    CONSTRAINT jobs_currency_format          CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT jobs_price_cents_non_negative CHECK (price_cents >= 0),

    CONSTRAINT jobs_title_not_blank            CHECK (length(btrim(title))            > 0),
    CONSTRAINT jobs_description_not_blank      CHECK (length(btrim(description))      > 0),
    CONSTRAINT jobs_customer_name_not_blank    CHECK (length(btrim(customer_name))    > 0),
    CONSTRAINT jobs_customer_phone_not_blank   CHECK (length(btrim(customer_phone))   > 0),
    CONSTRAINT jobs_customer_address_not_blank CHECK (length(btrim(customer_address)) > 0)
);

CREATE INDEX jobs_originator_state_ix
    ON jobs (originator_user_id, state);

-- Partial: most jobs at most times have no current assignee, so the
-- index stays tiny and the dashboard query stays fast.
CREATE INDEX jobs_assignee_active_ix
    ON jobs (current_assignee_user_id, state)
    WHERE current_assignee_user_id IS NOT NULL;

CREATE INDEX jobs_state_category_ix
    ON jobs (state, service_category_id);

CREATE INDEX jobs_customer_location_gix
    ON jobs USING GIST (customer_location);


-- ---------------------------------------------------------------------
-- job_state_transitions: append-only audit. Every successful state
-- change appends a row. NEVER updated, NEVER deleted (except via CASCADE
-- on job delete).
--   * from_state is NULL on the row that records initial creation;
--     to_state is always set.
--   * actor_user_id is who performed the transition (NOT necessarily
--     the job's originator — a transfer recipient closing the job is
--     the actor for COMPLETED → CLOSED_PAID).
--   * reason is free-text optional, for human consumption only.
-- ---------------------------------------------------------------------
CREATE TABLE job_state_transitions (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id        BIGINT       NOT NULL,
    from_state    VARCHAR(32),
    to_state      VARCHAR(32)  NOT NULL,
    actor_user_id BIGINT       NOT NULL,
    reason        VARCHAR(500),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_jst_job
        FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,
    CONSTRAINT fk_jst_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT jst_to_state_valid CHECK (to_state IN (
        'OPEN_GENERAL', 'OPEN_TODAY', 'PENDING_TRANSFER', 'ASSIGNED',
        'IN_PROGRESS',  'COMPLETED',  'CLOSED_PAID',      'CANCELLED', 'EXPIRED'
    )),
    CONSTRAINT jst_from_state_valid CHECK (from_state IS NULL OR from_state IN (
        'OPEN_GENERAL', 'OPEN_TODAY', 'PENDING_TRANSFER', 'ASSIGNED',
        'IN_PROGRESS',  'COMPLETED',  'CLOSED_PAID',      'CANCELLED', 'EXPIRED'
    ))
);

CREATE INDEX jst_job_occurred_ix
    ON job_state_transitions (job_id, occurred_at DESC);

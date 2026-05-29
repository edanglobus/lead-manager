-- =====================================================================
-- V9__create_transfers.sql
-- The transfer chain: one edge in the network of "User A hands a lead
-- to User B for X% commission." Job + Transfer together capture the
-- marketplace's chain-of-custody so the slice 8 ledger can walk it
-- and distribute payouts.
--
-- Notes:
--   * status is VARCHAR(32) + CHECK, same rationale as jobs.state in
--     V8 (Postgres ENUMs are painful to evolve under concurrent reader
--     load; CHECK lets us add a status with a normal column-rewrite-
--     free migration).
--
--   * commission_pct is DECIMAL(5,2) — NEVER float/double for money-
--     adjacent values per CLAUDE.md. Five total digits with two after
--     the decimal point allows 000.00 .. 100.00 exactly.
--
--   * pre_transfer_state captures what state the job was in BEFORE
--     the transfer was proposed. On DECLINED or CANCELLED, the
--     service tier restores the job to this state — without it, a
--     declined transfer would have to guess whether the job had been
--     OPEN_GENERAL, OPEN_TODAY, or even ASSIGNED (re-transfer case).
--     Keeping the previous state on the transfer row makes the rewind
--     deterministic and the state machine pure (no implicit "remember
--     where we came from" magic).
--
--   * The PARTIAL UNIQUE INDEX on (job_id) WHERE status='PROPOSED'
--     is the source of truth for "at most one open proposal per job."
--     Two concurrent proposes will fail with a 23505 unique violation
--     at COMMIT; the service tier maps that to a friendly 409. Doing
--     the check at the DB is what makes the no-double-proposal rule
--     safe under racing requests; a service-tier check alone would
--     race.
--
--   * CHECK from_user_id <> to_user_id stops the silly case of
--     "transfer this job to myself."
--
--   * FK to jobs is ON DELETE CASCADE because a transfer row has no
--     meaning without the job it transfers. FKs to users are
--     ON DELETE RESTRICT — a user can't be hard-deleted while
--     they're a party to a transfer (history would dangle).
--
-- Indexes:
--   * transfers_open_per_job_uq   - partial unique, see above
--   * transfers_job_ix            - history view: "all transfers for job X"
--   * transfers_to_user_pending_ix - "transfers awaiting my response"
--     (partial, only PROPOSED rows — small index, fast inbox query)
-- =====================================================================

CREATE TABLE transfers (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id              BIGINT       NOT NULL,

    from_user_id        BIGINT       NOT NULL,
    to_user_id          BIGINT       NOT NULL,

    commission_pct      DECIMAL(5,2) NOT NULL,
    status              VARCHAR(32)  NOT NULL,

    -- Where the job came from. Used to restore on DECLINED / CANCELLED.
    pre_transfer_state  VARCHAR(32)  NOT NULL,

    proposed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at          TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_transfers_job
        FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,
    CONSTRAINT fk_transfers_from_user
        FOREIGN KEY (from_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_to_user
        FOREIGN KEY (to_user_id) REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT transfers_distinct_parties
        CHECK (from_user_id <> to_user_id),

    CONSTRAINT transfers_commission_range
        CHECK (commission_pct >= 0 AND commission_pct <= 100),

    CONSTRAINT transfers_status_valid CHECK (status IN (
        'PROPOSED', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED'
    )),

    -- The pre-transfer state is one of the job states a transfer can
    -- "rewind to". An ASSIGNED job can be re-transferred (slice 4's
    -- state machine allows ASSIGNED -> PENDING_TRANSFER), so ASSIGNED
    -- is a valid pre-state too. Terminal states cannot be pre-states
    -- since you can't transfer a closed/cancelled/expired job.
    CONSTRAINT transfers_pre_state_valid CHECK (pre_transfer_state IN (
        'OPEN_GENERAL', 'OPEN_TODAY', 'ASSIGNED'
    ))
);

-- The partial unique index that DB-enforces "at most one open
-- proposal per job." The constraint is the source of truth; the
-- service-tier check is a UX convenience.
CREATE UNIQUE INDEX transfers_open_per_job_uq
    ON transfers (job_id)
    WHERE status = 'PROPOSED';

-- B-tree for "show me all transfers for job X" (history view).
CREATE INDEX transfers_job_ix
    ON transfers (job_id, id DESC);

-- Partial index for the recipient's inbox query: "transfers awaiting
-- my response." Only PROPOSED rows are indexed, so the index stays
-- tiny and the query is a single-key probe.
CREATE INDEX transfers_to_user_pending_ix
    ON transfers (to_user_id)
    WHERE status = 'PROPOSED';

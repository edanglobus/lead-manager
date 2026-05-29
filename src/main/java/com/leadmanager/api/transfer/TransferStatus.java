package com.leadmanager.api.transfer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle states of a {@link Transfer}.
 * <p>
 * Much smaller machine than {@link com.leadmanager.api.job.JobState}: a
 * transfer is born {@code PROPOSED} and resolves to exactly one
 * terminal outcome. There is no path back from any terminal to
 * {@code PROPOSED}: a declined / cancelled / expired transfer is
 * dead, and a new proposal must be created (with its own row) to
 * try again.
 * <p>
 * <b>The flow.</b>
 * <pre>
 *                 ┌── ACCEPTED   (terminal — happy path)
 *                 │
 *   PROPOSED ─────┼── DECLINED   (terminal — recipient said no)
 *                 │
 *                 ├── CANCELLED  (terminal — proposer rescinded)
 *                 │
 *                 └── EXPIRED    (terminal — TTL ran out, slice 6)
 * </pre>
 * <p>
 * <b>Wire stability.</b> {@link #name()} is stored in the
 * {@code transfers.status} VARCHAR column (V9 CHECK constraint) and
 * serialised to JSON. Adding a new status requires both a Java enum
 * constant and a V{n} migration widening the CHECK — those two
 * changes belong in the same commit.
 */
public enum TransferStatus {

    PROPOSED,
    ACCEPTED,
    DECLINED,
    CANCELLED,
    EXPIRED;

    /**
     * Allowed-transitions matrix. Terminal states map to an empty set
     * (never null) so callers iterate safely.
     */
    private static final Map<TransferStatus, Set<TransferStatus>> ALLOWED;

    static {
        ALLOWED = new EnumMap<>(TransferStatus.class);
        ALLOWED.put(PROPOSED,  EnumSet.of(ACCEPTED, DECLINED, CANCELLED, EXPIRED));
        ALLOWED.put(ACCEPTED,  EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(DECLINED,  EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(EXPIRED,   EnumSet.noneOf(TransferStatus.class));
    }

    public boolean canTransitionTo(TransferStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<TransferStatus> allowedNextStates() {
        return EnumSet.copyOf(ALLOWED.get(this));
    }

    /** A terminal status has no outgoing transitions. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}

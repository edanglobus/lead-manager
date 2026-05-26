package com.leadmanager.api.job;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The nine states a {@code Job} can occupy and the allowed transitions
 * between them.
 * <p>
 * The state machine is encoded directly on the enum so the legal moves
 * are co-located with the value definitions — there is no chance of a
 * service-tier caller inventing a transition that's not represented
 * here. {@link #canTransitionTo(JobState)} is the single source of
 * truth; the service layer must never check the transition by hand.
 * <p>
 * <b>The marketplace flow.</b>
 * <pre>
 *   OPEN_GENERAL ─┐
 *                 ├─► PENDING_TRANSFER ─► ASSIGNED ─► IN_PROGRESS ─► COMPLETED ─► CLOSED_PAID
 *   OPEN_TODAY  ─┘                                                       │
 *                                                                        ├─► CANCELLED  (terminal failure)
 *                                                                        └─► EXPIRED    (terminal failure)
 * </pre>
 * Branches not shown:
 * <ul>
 *   <li>A {@code PENDING_TRANSFER} can be declined back to either of the
 *       two OPEN states (the service layer picks based on the original
 *       state).</li>
 *   <li>An {@code ASSIGNED} job can be re-transferred ({@code ASSIGNED → PENDING_TRANSFER}).</li>
 *   <li>Any non-terminal state can be CANCELLED.</li>
 *   <li>Any open or scheduled state can EXPIRE on a TTL.</li>
 * </ul>
 * <p>
 * <b>Wire stability.</b> {@link #name()} is what gets stored in the
 * {@code state} VARCHAR column of {@code jobs} (per the V8 migration's
 * CHECK constraint) and what's serialised to JSON. Adding a new state
 * requires both a Java enum constant AND a V{n} migration that widens
 * the CHECK constraint — those two changes belong in the same commit.
 */
public enum JobState {

    OPEN_GENERAL,
    OPEN_TODAY,
    PENDING_TRANSFER,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    CLOSED_PAID,
    CANCELLED,
    EXPIRED;

    /**
     * The allowed-transitions matrix. EnumMap + EnumSet keep the lookup
     * O(1) and the memory footprint tiny. Terminal states map to an
     * empty set, NEVER to null — callers can iterate without a null
     * check.
     */
    private static final Map<JobState, Set<JobState>> ALLOWED;

    static {
        ALLOWED = new EnumMap<>(JobState.class);

        // Two parallel "open" states differ only in urgency. Both can
        // be self-assigned, transferred, cancelled, or expired.
        ALLOWED.put(OPEN_GENERAL,     EnumSet.of(PENDING_TRANSFER, ASSIGNED, CANCELLED, EXPIRED));
        ALLOWED.put(OPEN_TODAY,       EnumSet.of(PENDING_TRANSFER, ASSIGNED, CANCELLED, EXPIRED));

        // A pending transfer can be accepted (→ ASSIGNED), declined
        // back to either OPEN state (the service layer picks which),
        // cancelled by the originator, or expire on TTL.
        ALLOWED.put(PENDING_TRANSFER, EnumSet.of(ASSIGNED, OPEN_GENERAL, OPEN_TODAY, CANCELLED, EXPIRED));

        // An assignee can start work, hand the job off again, or the
        // originator can cancel.
        ALLOWED.put(ASSIGNED,         EnumSet.of(IN_PROGRESS, PENDING_TRANSFER, CANCELLED));

        // Once in progress, the only ways out are completion or
        // cancellation (no re-transfer once the work has started).
        ALLOWED.put(IN_PROGRESS,      EnumSet.of(COMPLETED, CANCELLED));

        // Completed work gets paid. CANCELLED here covers the rare
        // refund / dispute path before the ledger entries land.
        ALLOWED.put(COMPLETED,        EnumSet.of(CLOSED_PAID, CANCELLED));

        // Three terminal states — explicit empty sets so the API is
        // never null.
        ALLOWED.put(CLOSED_PAID,      EnumSet.noneOf(JobState.class));
        ALLOWED.put(CANCELLED,        EnumSet.noneOf(JobState.class));
        ALLOWED.put(EXPIRED,          EnumSet.noneOf(JobState.class));
    }

    /**
     * Returns whether this state may transition directly to
     * {@code target}. False (not an exception) for illegal moves — the
     * service layer raises the {@code ApiException} with the right
     * error code so the wire contract stays uniform.
     */
    public boolean canTransitionTo(JobState target) {
        return ALLOWED.get(this).contains(target);
    }

    /**
     * The set of states reachable in one step. Defensively wrapped so
     * callers cannot mutate the underlying matrix.
     */
    public Set<JobState> allowedNextStates() {
        return EnumSet.copyOf(ALLOWED.get(this));
    }

    /**
     * A terminal state has no outgoing transitions. Useful for feed
     * queries that filter "still-active" leads.
     */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * "Open" leads — those discoverable on the public feed. Distinct
     * from non-terminal: an ASSIGNED job is non-terminal but not on
     * the feed anymore.
     */
    public boolean isOpen() {
        return this == OPEN_GENERAL || this == OPEN_TODAY;
    }
}

package com.leadmanager.api.job;

import java.util.List;

/**
 * Business operations on {@link Job} aggregates.
 * <p>
 * <b>One method per state transition</b>, by design. The alternative —
 * a single generic {@code transition(JobState target, ...)} method —
 * would push the per-transition authorization rule into a switch
 * statement or a lookup map, hiding "who can do what" from
 * reviewers. Five named methods keep the authz rule co-located with
 * the operation: the JavaDoc says who can call it, the implementation
 * enforces it, the unit test pins it.
 * <p>
 * <b>Authorization model (slice 4).</b> Visibility is limited to the
 * originator and the current assignee. Open-feed visibility (anyone
 * seeing leads in their trade) lands in slice 5 alongside the
 * transfer/blind-mask logic — slice 4 is the state machine alone.
 * <p>
 * <b>404 over 403.</b> Every "not authorized" path returns
 * {@link com.leadmanager.api.common.exception.ErrorCode#RESOURCE_NOT_FOUND},
 * never {@code FORBIDDEN}. The two cases are merged on purpose so an
 * attacker cannot enumerate other users' job ids by comparing
 * response codes.
 * <p>
 * <b>Concurrency.</b> Every state-changing method acquires a
 * pessimistic write lock via
 * {@link JobRepository#findByIdForUpdate(Long)}. The state machine
 * alone is not enough to prevent races — two callers can both read
 * "state=ASSIGNED" and both attempt {@code start()}. The row lock
 * serialises them; the loser sees the post-commit state and gets
 * {@link com.leadmanager.api.common.exception.ErrorCode#JOB_STATE_TRANSITION_NOT_ALLOWED}.
 */
public interface JobService {

    /**
     * Creates a new lead with {@code actorUserId} as the originator.
     * The initial state is derived from {@code command.today()}:
     * {@link JobState#OPEN_TODAY} when true, {@link JobState#OPEN_GENERAL}
     * otherwise. An initial transition row (from-state {@code null})
     * is written in the same transaction so the audit trail begins
     * on row one.
     * <p>
     * Throws {@code ApiException(RESOURCE_NOT_FOUND)} if the
     * referenced service category does not exist or is inactive.
     */
    Job create(Long actorUserId, JobCreateCommand command);

    /**
     * Returns a {@link JobAccess} for the job iff the caller has any
     * visibility on it: originator, current assignee, or transfer
     * candidate with an open proposal. The returned record carries
     * the {@link JobVisibilityPolicy.Visibility} so the HTTP layer
     * can dispatch to the right DTO shape (full vs masked).
     * <p>
     * 404 ({@code RESOURCE_NOT_FOUND}) when the job is missing OR the
     * caller has no visibility — same merged-cases rule (no
     * enumeration leak via response codes).
     */
    JobAccess findOne(Long callerUserId, Long jobId);

    /** Caller's own jobs as originator, newest-first. */
    List<Job> listMyOriginated(Long userId);

    /** Caller's own jobs as current assignee, newest-first. */
    List<Job> listMyAssigned(Long userId);

    /**
     * Originator self-assigns one of their open leads:
     * {@link JobState#OPEN_GENERAL} or {@link JobState#OPEN_TODAY}
     * → {@link JobState#ASSIGNED}, with {@code callerUserId}
     * recorded as the new assignee.
     * <p>
     * 404 when the caller is not the originator.
     * 409 (JOB_STATE_TRANSITION_NOT_ALLOWED) when the job is not in
     * an open state.
     */
    Job selfAssign(Long callerUserId, Long jobId);

    /**
     * Current assignee starts work:
     * {@link JobState#ASSIGNED} → {@link JobState#IN_PROGRESS}.
     * 404 when the caller is not the current assignee;
     * 409 otherwise.
     */
    Job start(Long callerUserId, Long jobId);

    /**
     * Current assignee marks work complete:
     * {@link JobState#IN_PROGRESS} → {@link JobState#COMPLETED}.
     * 404 when caller is not the current assignee; 409 otherwise.
     */
    Job complete(Long callerUserId, Long jobId);

    /**
     * Originator closes a completed job (confirming payment received):
     * {@link JobState#COMPLETED} → {@link JobState#CLOSED_PAID}.
     * <p>
     * Slice 4 leaves the ledger updates to slice 8 — closing the job
     * here ONLY transitions the state. The future
     * {@code feat/ledger-and-commissions} slice will hook a domain
     * event off this transition to write the payout entries.
     * <p>
     * 404 when caller is not the originator; 409 otherwise.
     */
    Job close(Long callerUserId, Long jobId);

    /**
     * Cancels a non-terminal job. Either the originator OR the
     * current assignee may cancel — both have legitimate reasons
     * (customer flake, provider unavailability). The reason is
     * recorded on the transition row for the audit trail.
     * <p>
     * 404 when caller is neither originator nor current assignee;
     * 409 when the job is already terminal.
     */
    Job cancel(Long callerUserId, Long jobId, String reason);
}

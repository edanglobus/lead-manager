package com.leadmanager.api.transfer;

import java.util.List;

/**
 * Business operations on {@link Transfer} aggregates.
 * <p>
 * One method per state transition (same pattern as {@code JobService})
 * so the per-method authorization rule stays co-located with the
 * operation it gates:
 * <ul>
 *   <li>{@link #propose} — actor is the job's originator or current
 *       assignee. Moves the job to {@code PENDING_TRANSFER}, creates
 *       a transfer in {@code PROPOSED}.</li>
 *   <li>{@link #accept} — actor is the transfer's {@code to_user}.
 *       Moves transfer to {@code ACCEPTED}, moves job to
 *       {@code ASSIGNED} with the new assignee.</li>
 *   <li>{@link #decline} — actor is {@code to_user}. Transfer
 *       {@code DECLINED}, job restored to its
 *       {@code pre_transfer_state}.</li>
 *   <li>{@link #cancel} — actor is the proposer ({@code from_user}).
 *       Same rewind as decline.</li>
 *   <li>{@link #historyForJob} — chain history for a job, visible
 *       to anyone who can see the job at all (originator, current
 *       assignee, transfer candidate).</li>
 * </ul>
 * <p>
 * <b>404 over 403</b> on every failed authorization, same merged-cases
 * rule as elsewhere — no enumeration leak.
 * <p>
 * <b>Concurrency.</b> Every mutating method acquires a pessimistic
 * write lock on the relevant Job AND Transfer rows (where both
 * exist) so a racing accept-vs-decline cannot corrupt the chain.
 * Lock ordering is always Job-then-Transfer to avoid deadlocks
 * across operations.
 * <p>
 * <b>The DB closes the race.</b> The partial unique index
 * {@code transfers_open_per_job_uq} on
 * {@code (job_id) WHERE status='PROPOSED'} is the source of truth for
 * "at most one open proposal per job." {@link #propose} pre-checks
 * for a friendly 409, but two concurrent proposers will see one
 * succeed and the other fail with a {@code 23505} that this service
 * also maps to 409.
 */
public interface TransferService {

    /**
     * Proposes transferring the job to {@code command.toUserId} at
     * {@code command.commissionPct}. The actor must be the job's
     * originator or current assignee.
     * <p>
     * Throws:
     * <ul>
     *   <li>404 {@code RESOURCE_NOT_FOUND} when the job is missing or
     *       the actor has no relationship to it, or the {@code toUserId}
     *       does not exist.</li>
     *   <li>409 {@code JOB_STATE_TRANSITION_NOT_ALLOWED} when the
     *       job is not in a state that can move to
     *       {@code PENDING_TRANSFER}.</li>
     *   <li>409 {@code OPEN_TRANSFER_ALREADY_EXISTS} when there is
     *       already a {@code PROPOSED} transfer for this job.</li>
     *   <li>400 {@code VALIDATION_FAILED} when proposer == candidate
     *       (also enforced by the DB CHECK
     *       {@code transfers_distinct_parties}).</li>
     * </ul>
     */
    Transfer propose(Long actorUserId, TransferProposeCommand command);

    /**
     * Candidate accepts the transfer. Transfer → {@code ACCEPTED},
     * job → {@code ASSIGNED} with the candidate as assignee.
     * 404 when the actor is not the {@code to_user}; 409 when the
     * transfer is no longer {@code PROPOSED}.
     */
    Transfer accept(Long actorUserId, Long transferId);

    /**
     * Candidate declines. Transfer → {@code DECLINED}, job restored
     * to the {@code pre_transfer_state} recorded on the row. 404
     * when actor is not the {@code to_user}; 409 when the transfer
     * is no longer {@code PROPOSED}.
     */
    Transfer decline(Long actorUserId, Long transferId);

    /**
     * Proposer rescinds. Transfer → {@code CANCELLED}, job restored
     * to the {@code pre_transfer_state}. 404 when actor is not the
     * {@code from_user}; 409 when the transfer is no longer
     * {@code PROPOSED}.
     */
    Transfer cancel(Long actorUserId, Long transferId);

    /**
     * Returns the chain history for a job newest-first.
     * <p>
     * Visibility: caller must have any visibility on the job
     * (originator, current assignee, or current transfer candidate).
     * Otherwise 404.
     */
    List<Transfer> historyForJob(Long callerUserId, Long jobId);
}

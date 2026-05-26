package com.leadmanager.api.job;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

/**
 * Persistence port for {@link Job}.
 * <p>
 * Two read paths matter:
 * <ul>
 *   <li>{@link #findById(Object)} — optimistic flow. The caller reads,
 *       mutates, and saves; Hibernate compares {@code version} and
 *       raises {@code OptimisticLockingFailureException} on a
 *       concurrent edit.</li>
 *   <li>{@link #findByIdForUpdate(Long)} — pessimistic flow for state
 *       transitions where racing callers would corrupt the chain
 *       (transfer accept-vs-decline, simultaneous state changes).
 *       Acquires a row-level {@code SELECT FOR UPDATE} that blocks
 *       conflicting writers until the transaction commits.</li>
 * </ul>
 * The state machine alone (see {@link JobState#canTransitionTo})
 * is not sufficient to prevent races: two threads can both read
 * "state=ASSIGNED" and both call {@code moveTo(IN_PROGRESS)} — the
 * second wins silently under optimistic locking ONLY if the version
 * check is also performed. Pessimistic locking is the belt to the
 * version's braces for the transitions that matter.
 */
@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM Job j WHERE j.id = :id")
    Optional<Job> findByIdForUpdate(@Param("id") Long id);

    /**
     * "My listings" view: jobs the caller created. Order by id DESC
     * (rather than created_at DESC) so the result is deterministic
     * across rows inserted in the same millisecond. Hits the
     * {@code jobs_originator_state_ix} composite index for the
     * leading column scan.
     */
    List<Job> findAllByOriginatorUserIdOrderByIdDesc(Long originatorUserId);

    /**
     * "My work" view: jobs currently assigned to the caller. The
     * partial index {@code jobs_assignee_active_ix} on
     * {@code (current_assignee_user_id, state) WHERE current_assignee_user_id IS NOT NULL}
     * keeps this lookup fast — only rows that have an assignee are
     * indexed at all.
     */
    List<Job> findAllByCurrentAssigneeUserIdOrderByIdDesc(Long assigneeUserId);
}

package com.leadmanager.api.job;

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
}

package com.leadmanager.api.transfer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

/**
 * Persistence port for {@link Transfer}.
 * <p>
 * Three read paths matter:
 * <ul>
 *   <li>{@link #findByIdForUpdate(Long)} — pessimistic write lock for
 *       accept / decline / cancel, racing-callers safe. Pairs with
 *       {@code JobRepository.findByIdForUpdate(Long)} when the
 *       service tier needs to mutate both rows.</li>
 *   <li>{@link #findOpenProposalByJobId(Long)} — "does this job have
 *       an open proposal already?" Hits the partial unique index
 *       {@code transfers_open_per_job_uq}. The service uses this for
 *       a UX-friendly 409 BEFORE attempting the insert that would
 *       otherwise fail with a 23505 from the DB constraint.</li>
 *   <li>{@link #findAllByJobIdOrderByIdDesc(Long)} — chain history
 *       view for {@code GET /jobs/{id}/transfers}.</li>
 * </ul>
 */
@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transfer t WHERE t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") Long id);

    /**
     * Returns the single open ({@code PROPOSED}) transfer for a job,
     * or empty. The partial unique index {@code transfers_open_per_job_uq}
     * guarantees at most one row matches, so {@link Optional} is the
     * right return type (not a list).
     */
    Optional<Transfer> findFirstByJobIdAndStatus(Long jobId, TransferStatus status);

    /**
     * Chain history newest-first, for the
     * {@code GET /jobs/{id}/transfers} endpoint. Hits the composite
     * {@code transfers_job_ix} on {@code (job_id, id DESC)}.
     */
    List<Transfer> findAllByJobIdOrderByIdDesc(Long jobId);
}

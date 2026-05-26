package com.leadmanager.api.job;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence port for the append-only {@link JobStateTransition}
 * audit table.
 * <p>
 * The repository is intentionally narrow: writes are
 * {@link #save(Object)} only (audit rows are immutable — no update or
 * delete) and the single read returns a job's history newest-first.
 * The composite index {@code jst_job_occurred_ix} on
 * {@code (job_id, occurred_at DESC)} from the V8 migration is what
 * makes that query a single index probe.
 */
@Repository
public interface JobStateTransitionRepository extends JpaRepository<JobStateTransition, Long> {

    List<JobStateTransition> findAllByJobIdOrderByOccurredAtDesc(Long jobId);
}

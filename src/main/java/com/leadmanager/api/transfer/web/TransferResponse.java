package com.leadmanager.api.transfer.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.leadmanager.api.job.JobState;
import com.leadmanager.api.transfer.TransferStatus;

/**
 * Wire representation of a {@link com.leadmanager.api.transfer.Transfer}.
 * <p>
 * Exposes all the fields a caller needs to render a transfer card
 * (who proposed, who's the candidate, the commission cut, the
 * current status, and the rewind target). {@code preTransferState}
 * is included so a UI can show "if I decline, the job goes back
 * to OPEN_GENERAL" without a second round-trip.
 *
 * @param id                  row id
 * @param jobId               the job being offered
 * @param fromUserId          proposer (originator or current assignee at propose time)
 * @param toUserId            candidate
 * @param commissionPct       cut as {@code BigDecimal} percentage
 * @param status              {@link TransferStatus}, serialised by name
 * @param preTransferState    state the job will revert to on decline / cancel
 * @param createdAt           when the transfer was proposed
 * @param decidedAt           when it resolved (null while {@code PROPOSED})
 * @param expiresAt           optional TTL deadline (slice 6 +)
 */
public record TransferResponse(
        Long id,
        Long jobId,
        Long fromUserId,
        Long toUserId,
        BigDecimal commissionPct,
        TransferStatus status,
        JobState preTransferState,
        Instant createdAt,
        Instant decidedAt,
        Instant expiresAt
) {
}

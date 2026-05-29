package com.leadmanager.api.transfer;

import java.math.BigDecimal;

/**
 * Immutable input port to
 * {@link TransferService#propose(Long, TransferProposeCommand)}.
 * <p>
 * Distinct from any HTTP body shape so the service is reusable from
 * non-HTTP callers (tests, future CSV importers, slice 11's
 * idempotency replays) without dragging Jakarta Validation or
 * Jackson into the domain.
 *
 * @param jobId          the job being offered
 * @param toUserId       the candidate provider
 * @param commissionPct  cut as a percentage in [0, 100]. Kept as
 *                       BigDecimal per CLAUDE.md — never double for
 *                       money-adjacent values.
 */
public record TransferProposeCommand(
        Long jobId,
        Long toUserId,
        BigDecimal commissionPct
) {
}

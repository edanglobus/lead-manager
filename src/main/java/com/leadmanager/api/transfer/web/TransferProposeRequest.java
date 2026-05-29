package com.leadmanager.api.transfer.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * HTTP request body for
 * {@code POST /api/v1/jobs/{jobId}/transfers}.
 * <p>
 * The {@code jobId} comes from the URL path variable — it isn't on
 * this DTO. The service composes the
 * {@link com.leadmanager.api.transfer.TransferProposeCommand} from
 * (path jobId, request body, authenticated caller).
 *
 * @param toUserId       id of the candidate provider
 * @param commissionPct  cut as a percentage in {@code [0.00, 100.00]},
 *                       up to two decimal places. {@code BigDecimal}
 *                       per CLAUDE.md — never {@code double} for money-
 *                       adjacent values.
 */
public record TransferProposeRequest(

        @NotNull(message = "toUserId is required")
        @Positive(message = "toUserId must be positive")
        Long toUserId,

        @NotNull(message = "commissionPct is required")
        @DecimalMin(value = "0.00",   message = "commissionPct must be >= 0")
        @DecimalMax(value = "100.00", message = "commissionPct must be <= 100")
        @Digits(integer = 3, fraction = 2,
                message = "commissionPct must have at most 2 decimal places")
        BigDecimal commissionPct
) {
}

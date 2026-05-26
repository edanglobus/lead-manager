package com.leadmanager.api.job;

import java.time.Instant;

/**
 * Immutable input port to
 * {@link JobService#create(Long, JobCreateCommand)}.
 * <p>
 * Distinct from any HTTP body shape so the service is reusable from
 * non-HTTP callers (tests, future CSV importers) without dragging
 * Bean Validation or Jackson into the domain. The HTTP layer
 * performs format validation (lat/lng bounds, non-blank strings,
 * price ≥ 0) and projects into this command via the mapper.
 * <p>
 * <b>Why {@code today} instead of letting the caller pass a {@link JobState}.</b>
 * Callers should not pick from the internal state vocabulary — they
 * should describe what they MEAN (the lead is for today vs. no specific
 * deadline) and let the service derive the right state. Same reason
 * "active" is a boolean on a user, not a {@code UserStatus} enum
 * with valid values "ACTIVE" and "INACTIVE" — the boolean carries
 * the intent without the surface area to misuse.
 *
 * @param serviceCategoryId  trade the lead is for
 * @param today              {@code true} → {@link JobState#OPEN_TODAY},
 *                           {@code false} → {@link JobState#OPEN_GENERAL}
 * @param title              short headline ("Leaking faucet")
 * @param description        free-text problem details
 * @param customerName       customer's display name
 * @param customerPhone      customer's contact number
 * @param customerAddress    free-text street address (for the provider to navigate)
 * @param customerLatitude   WGS84 latitude  of the customer location
 * @param customerLongitude  WGS84 longitude of the customer location
 * @param priceCents         agreed price in minor units (cents/agorot/...)
 * @param currency           ISO 4217 alpha-3 code (e.g. "USD")
 * @param scheduledFor       optional intended start time; may be null
 *                           (always null for {@code OPEN_GENERAL} in practice,
 *                           but the service does not enforce that combo —
 *                           the HTTP layer may add a stricter rule later)
 */
public record JobCreateCommand(
        Long serviceCategoryId,
        boolean today,
        String title,
        String description,
        String customerName,
        String customerPhone,
        String customerAddress,
        double customerLatitude,
        double customerLongitude,
        long priceCents,
        String currency,
        Instant scheduledFor
) {
}

package com.leadmanager.api.job.web;

import java.time.Instant;

import com.leadmanager.api.job.JobState;

/**
 * Wire representation of a {@link com.leadmanager.api.job.Job} for a
 * caller who is a transfer candidate (a user with an open transfer
 * proposal addressed to them) but is NOT the originator or the
 * current assignee.
 * <p>
 * <b>Privacy contract.</b> The fields {@code customerAddress} and
 * {@code customerPhone} are {@i absent} from this record entirely
 * (not nulled). A consumer that doesn't have access to those
 * fields can't render placeholders for them — and a misbehaving
 * client can't display data it never received over the wire. This
 * is the server-side half of the CLAUDE.md §1 blind-transfer rule.
 * <p>
 * {@code customerName} stays visible — a candidate needs trust
 * signals ("this is for Jane Doe, looks legit") and the name alone
 * cannot bypass the platform. {@code customerLatitude} /
 * {@code customerLongitude} stay visible too: a rough map pin
 * is essential for "is this job in my service area"; the precise
 * address (which is what enables a direct DM to the customer
 * bypassing the platform) is the part that is withheld.
 */
public record MaskedJobResponse(
        Long id,
        Long originatorUserId,
        Long currentAssigneeUserId,
        Long serviceCategoryId,
        JobState state,
        String title,
        String description,
        String customerName,
        // customerPhone   - INTENTIONALLY ABSENT
        // customerAddress - INTENTIONALLY ABSENT
        double customerLatitude,
        double customerLongitude,
        Long priceCents,
        String currency,
        Instant scheduledFor,
        Instant createdAt
) {
}

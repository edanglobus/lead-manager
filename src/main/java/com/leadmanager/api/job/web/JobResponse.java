package com.leadmanager.api.job.web;

import java.time.Instant;

import com.leadmanager.api.job.JobState;

/**
 * Wire representation of a {@link com.leadmanager.api.job.Job}.
 * <p>
 * <b>Slice 4 visibility.</b> The service tier only exposes a job to
 * its originator or current assignee, so this DTO carries the full
 * record without redaction. The blind-mask logic — hiding
 * {@code customerAddress} and {@code customerPhone} from a transfer
 * candidate before they accept — lands in slice 5, at which point
 * this DTO gains a redacted sibling produced by the same mapper.
 * <p>
 * Customer geo is exposed as latitude/longitude scalars, NOT as a
 * GeoJSON point — the mobile + web clients hand it straight to
 * Google/Apple/OSM mapping SDKs in that shape.
 *
 * @param id                       row id
 * @param originatorUserId         creator of the lead
 * @param currentAssigneeUserId    current holder (null when open or terminal-without-assignment)
 * @param serviceCategoryId        trade
 * @param state                    {@link JobState}, serialised by name
 * @param title                    short headline
 * @param description              free-text problem details
 * @param customerName             customer's display name
 * @param customerPhone            customer's phone
 * @param customerAddress          street address
 * @param customerLatitude         WGS84 latitude
 * @param customerLongitude        WGS84 longitude
 * @param priceCents               agreed price in minor units
 * @param currency                 ISO 4217 alpha-3 currency code
 * @param scheduledFor             optional intended start time
 * @param createdAt                row creation timestamp (UTC)
 */
public record JobResponse(
        Long id,
        Long originatorUserId,
        Long currentAssigneeUserId,
        Long serviceCategoryId,
        JobState state,
        String title,
        String description,
        String customerName,
        String customerPhone,
        String customerAddress,
        double customerLatitude,
        double customerLongitude,
        Long priceCents,
        String currency,
        Instant scheduledFor,
        Instant createdAt
) {
}

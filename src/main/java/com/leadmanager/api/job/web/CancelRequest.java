package com.leadmanager.api.job.web;

import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST /api/v1/jobs/{id}/cancel}.
 * <p>
 * {@code reason} is optional — sometimes a cancellation has no good
 * narrative to record. When present it's bounded to 500 chars to
 * match the {@code reason} column on
 * {@code job_state_transitions}. When absent (or blank) it is
 * forwarded as {@code null}, which the audit row records as "no
 * reason given."
 */
public record CancelRequest(

        @Size(max = 500, message = "reason must be <= 500 characters")
        String reason
) {
}

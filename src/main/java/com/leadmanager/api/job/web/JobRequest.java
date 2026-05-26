package com.leadmanager.api.job.web;

import java.time.Instant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST /api/v1/jobs}.
 * <p>
 * Bean Validation runs before the controller body executes; any
 * constraint failure becomes a 400 RFC 7807 via
 * {@code GlobalExceptionHandler}. The service therefore never receives
 * malformed coordinates, blank strings, negative money, or junk
 * currency codes via HTTP — the DB CHECK constraints in V8 are
 * defence in depth.
 *
 * @param serviceCategoryId  id of an existing, active trade
 * @param today              {@code true} → {@link com.leadmanager.api.job.JobState#OPEN_TODAY},
 *                           {@code false} → {@link com.leadmanager.api.job.JobState#OPEN_GENERAL}
 * @param title              short headline, max 120 chars (matches column width)
 * @param description        free-text problem details
 * @param customerName       max 120 chars
 * @param customerPhone      max 32 chars
 * @param customerAddress    max 255 chars
 * @param customerLatitude   WGS84 latitude, [-90, 90]
 * @param customerLongitude  WGS84 longitude, [-180, 180]
 * @param priceCents         agreed price in minor units; non-negative
 * @param currency           ISO 4217 alpha-3 ({@code [A-Z]{3}})
 * @param scheduledFor       optional intended start time
 */
public record JobRequest(

        @NotNull(message = "serviceCategoryId is required")
        @Positive(message = "serviceCategoryId must be positive")
        Long serviceCategoryId,

        @NotNull(message = "today is required")
        Boolean today,

        @NotBlank(message = "title must not be blank")
        @Size(max = 120, message = "title must be <= 120 characters")
        String title,

        @NotBlank(message = "description must not be blank")
        String description,

        @NotBlank(message = "customerName must not be blank")
        @Size(max = 120, message = "customerName must be <= 120 characters")
        String customerName,

        @NotBlank(message = "customerPhone must not be blank")
        @Size(max = 32, message = "customerPhone must be <= 32 characters")
        String customerPhone,

        @NotBlank(message = "customerAddress must not be blank")
        @Size(max = 255, message = "customerAddress must be <= 255 characters")
        String customerAddress,

        @NotNull(message = "customerLatitude is required")
        @DecimalMin(value = "-90.0", message = "customerLatitude must be >= -90")
        @DecimalMax(value = "90.0",  message = "customerLatitude must be <= 90")
        Double customerLatitude,

        @NotNull(message = "customerLongitude is required")
        @DecimalMin(value = "-180.0", message = "customerLongitude must be >= -180")
        @DecimalMax(value = "180.0",  message = "customerLongitude must be <= 180")
        Double customerLongitude,

        @NotNull(message = "priceCents is required")
        @PositiveOrZero(message = "priceCents must be >= 0")
        Long priceCents,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be ISO 4217 alpha-3 (e.g. USD)")
        String currency,

        Instant scheduledFor
) {
}

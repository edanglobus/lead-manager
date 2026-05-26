package com.leadmanager.api.servicearea.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * HTTP request body for {@code POST /api/v1/service-areas}.
 * <p>
 * Bean Validation runs before the controller body executes; any constraint
 * failure becomes a 400 RFC 7807 via {@code GlobalExceptionHandler}. The
 * service therefore never receives malformed coordinates or out-of-range
 * radii via HTTP — the DB CHECK constraint is just defence in depth.
 *
 * @param serviceCategoryId id of an existing, active trade
 * @param latitude          WGS84 latitude in degrees, [-90, 90]
 * @param longitude         WGS84 longitude in degrees, [-180, 180]
 * @param radiusMeters      circle radius in meters; bounded [100, 200000]
 *                          to match the DB CHECK constraint
 */
public record ServiceAreaRequest(

        @NotNull(message = "serviceCategoryId is required")
        @Positive(message = "serviceCategoryId must be positive")
        Long serviceCategoryId,

        @NotNull(message = "latitude is required")
        @DecimalMin(value = "-90.0",  message = "latitude must be >= -90")
        @DecimalMax(value = "90.0",   message = "latitude must be <= 90")
        Double latitude,

        @NotNull(message = "longitude is required")
        @DecimalMin(value = "-180.0", message = "longitude must be >= -180")
        @DecimalMax(value = "180.0",  message = "longitude must be <= 180")
        Double longitude,

        @NotNull(message = "radiusMeters is required")
        @Min(value = 100,    message = "radiusMeters must be >= 100")
        @Max(value = 200000, message = "radiusMeters must be <= 200000")
        Integer radiusMeters
) {
}

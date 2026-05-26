package com.leadmanager.api.servicearea;

/**
 * Immutable input port to {@link ServiceAreaService#create(Long, ServiceAreaCreateCommand)}.
 * <p>
 * Distinct from the HTTP {@code ServiceAreaRequest} DTO so the service can
 * be exercised from non-HTTP callers (tests, future batch importers) without
 * importing Bean Validation or Jackson into the domain. The HTTP layer
 * performs format validation (lat/lng/radius bounds), then projects into
 * this command via the mapper.
 *
 * @param serviceCategoryId the trade the area is for
 * @param latitude          WGS84 latitude, -90.0 ≤ lat ≤ 90.0
 * @param longitude         WGS84 longitude, -180.0 ≤ lng ≤ 180.0
 * @param radiusMeters      circle radius in meters; service-layer accepts
 *                          any positive int, the DB enforces [100, 200000]
 */
public record ServiceAreaCreateCommand(
        Long serviceCategoryId,
        double latitude,
        double longitude,
        int radiusMeters
) {
}

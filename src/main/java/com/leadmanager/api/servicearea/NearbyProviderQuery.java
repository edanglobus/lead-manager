package com.leadmanager.api.servicearea;

/**
 * Immutable input port to
 * {@link ServiceAreaService#findNearbyProviders(NearbyProviderQuery)}.
 * <p>
 * Distinct from any HTTP query-param shape so the service is reusable
 * from non-HTTP callers (tests, future batch jobs) without dragging
 * Bean Validation into the domain. The HTTP layer is responsible for
 * enforcing param bounds (lat/lng, positive ids, sensible limit) before
 * projecting into this command.
 *
 * @param latitude          WGS84 latitude, -90.0 ≤ lat ≤ 90.0
 * @param longitude         WGS84 longitude, -180.0 ≤ lng ≤ 180.0
 * @param serviceCategoryId the trade to search within
 * @param limit             max rows to return; the service treats any
 *                          positive value as legal, the HTTP layer caps it
 */
public record NearbyProviderQuery(
        double latitude,
        double longitude,
        long serviceCategoryId,
        int limit
) {
}

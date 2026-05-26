package com.leadmanager.api.servicearea.web;

import java.time.Instant;

/**
 * Public JSON view of a {@link com.leadmanager.api.servicearea.ServiceArea}.
 * <p>
 * The PostGIS {@code geography(Point,4326)} center is flattened back into
 * {@code latitude} / {@code longitude} fields here — clients never deal in
 * WKT or GeoJSON unless we deliberately extend the contract later.
 * <p>
 * Deliberately omitted:
 * <ul>
 *   <li>{@code version} — JPA optimistic-lock counter, implementation detail.</li>
 *   <li>{@code updatedAt} — would become meaningful once an UPDATE endpoint
 *       lands; for v1 (create + delete only) it equals {@code createdAt}.</li>
 * </ul>
 *
 * @param id                  server-assigned area id
 * @param userId              the provider who owns this area
 * @param serviceCategoryId   trade this area covers
 * @param latitude            center latitude (extracted from the Point)
 * @param longitude           center longitude (extracted from the Point)
 * @param radiusMeters        circle radius in meters
 * @param createdAt           when the area was created, UTC
 */
public record ServiceAreaResponse(
        Long id,
        Long userId,
        Long serviceCategoryId,
        double latitude,
        double longitude,
        Integer radiusMeters,
        Instant createdAt
) {
}

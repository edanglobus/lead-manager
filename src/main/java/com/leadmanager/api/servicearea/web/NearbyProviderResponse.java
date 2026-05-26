package com.leadmanager.api.servicearea.web;

/**
 * One row of the response body for {@code GET /api/v1/providers/nearby}.
 * <p>
 * Discloses only opaque identifiers and the geo math the caller needs
 * to render results — no provider name, phone, email, or rating. The
 * frontend resolves the provider profile via a follow-up call when the
 * user explicitly selects a match.
 *
 * @param userId          the matching provider's id
 * @param serviceAreaId   the specific area that matched (closest one if
 *                        the provider has several in the trade)
 * @param distanceMeters  great-circle distance from the query point to
 *                        the area's center, in meters
 * @param radiusMeters    the matched area's declared radius, in meters
 */
public record NearbyProviderResponse(
        long userId,
        long serviceAreaId,
        double distanceMeters,
        int radiusMeters
) {
}

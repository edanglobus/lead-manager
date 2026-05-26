package com.leadmanager.api.servicearea;

/**
 * Immutable value object representing one provider whose service area
 * covers the query point. Returned by
 * {@link ServiceAreaService#findNearbyProviders(NearbyProviderQuery)}.
 * <p>
 * Deliberately discloses {@code userId} only — never name, phone, or
 * address. Engagement flows (transfers, ratings) look up the relevant
 * provider profile through their own service when the user explicitly
 * acts on a match. Pre-loading PII here would leak provider identity
 * to anyone who can hit the public-ish "nearby" endpoint with any
 * authenticated token.
 *
 * @param userId          the matching provider's id
 * @param serviceAreaId   the specific area that matched (closest one if
 *                        the provider has several in the trade)
 * @param distanceMeters  great-circle distance from the query point to
 *                        the area's center, in meters
 * @param radiusMeters    the matched area's declared radius, in meters
 */
public record NearbyProviderMatch(
        long userId,
        long serviceAreaId,
        double distanceMeters,
        int radiusMeters
) {
}

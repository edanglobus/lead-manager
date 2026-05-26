package com.leadmanager.api.servicearea;

import java.util.List;

/**
 * Business operations on {@link ServiceArea} aggregates.
 * <p>
 * Each method takes the acting user's id explicitly: ownership is a service-
 * tier concern, NOT a controller concern. Pushing it down here means a
 * future caller (a CLI, a batch job, a Slack bot) cannot accidentally
 * create, list, or delete an area belonging to the wrong user.
 */
public interface ServiceAreaService {

    /**
     * Creates a new area for {@code userId} in the trade given by
     * {@link ServiceAreaCreateCommand#serviceCategoryId()}.
     * <p>
     * Throws {@code ApiException(RESOURCE_NOT_FOUND)} if the referenced
     * service category does not exist or is inactive. The DB CHECK
     * constraint enforces the radius bounds; if Bean Validation upstream
     * is bypassed (e.g. a non-HTTP caller), the violation surfaces as a
     * {@code DataIntegrityViolationException} rather than a friendlier
     * domain error — by design, since that's truly a programmer mistake.
     */
    ServiceArea create(Long userId, ServiceAreaCreateCommand command);

    /**
     * Returns the calling user's own areas, newest-first. The list is
     * scoped to {@code userId} at the repository — a caller cannot read
     * another user's areas through this path.
     */
    List<ServiceArea> listMine(Long userId);

    /**
     * Deletes the area identified by {@code areaId} iff it is owned by
     * {@code userId}. Throws {@code ApiException(RESOURCE_NOT_FOUND)} when
     * the area does not exist OR belongs to someone else — the two cases
     * are merged on purpose so an attacker can't enumerate other users'
     * area ids by comparing 403 vs 404 responses.
     */
    void delete(Long userId, Long areaId);

    /**
     * Finds providers whose service area in the given trade covers the
     * given point, ordered by ascending distance from the query point.
     * <p>
     * Returns at most one match per provider (the closest one) even if
     * the provider declared several overlapping areas in the same
     * trade — for a consumer-facing "find providers near me" UI, the
     * caller cares about which providers can serve them, not which
     * specific geo zones matched.
     * <p>
     * The result is intentionally limited to non-PII fields
     * (see {@link NearbyProviderMatch}). Callers that need provider
     * names or contact info must follow up through the appropriate
     * profile service after the user explicitly selects a match.
     */
    List<NearbyProviderMatch> findNearbyProviders(NearbyProviderQuery query);
}

package com.leadmanager.api.servicearea;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence port for {@link ServiceArea}.
 * <p>
 * Ownership-aware queries deliberately take {@code userId} as a parameter so
 * the service tier never has to remember to filter. The
 * {@link #findByIdAndUserId(Long, Long)} method backs the secure delete
 * path: if the row exists but belongs to another user, the result is
 * {@link Optional#empty()} — the controller then surfaces a 404, never a
 * 403, so an attacker can't enumerate which {@code id} values belong to
 * other providers by comparing response codes.
 */
@Repository
public interface ServiceAreaRepository extends JpaRepository<ServiceArea, Long> {

    /**
     * Lists a user's areas newest-first. Order is by {@code id DESC} rather
     * than {@code created_at DESC} so the ordering remains deterministic
     * across rows inserted in the same millisecond.
     */
    List<ServiceArea> findAllByUserIdOrderByIdDesc(Long userId);

    Optional<ServiceArea> findByIdAndUserId(Long id, Long userId);

    /**
     * Returns the closest matching service area per provider whose circle
     * covers the given {@code (lat, lng)} point in the given trade,
     * ordered by ascending distance and capped at {@code limit} rows.
     * <p>
     * Implementation notes:
     * <ul>
     *   <li>{@code ST_MakePoint(lng, lat)} — PostGIS uses (X, Y) =
     *       (longitude, latitude). The repository contract takes "lat, lng"
     *       for caller readability and reverses them here once.</li>
     *   <li>The cast to {@code ::geography} is required so {@code ST_DWithin}
     *       interprets {@code radius_meters} as METERS (geometry would
     *       interpret it as degrees, which is meaningless).</li>
     *   <li>{@code DISTINCT ON (user_id)} de-duplicates providers who own
     *       multiple matching areas — the closest match wins. The inner
     *       {@code ORDER BY user_id, dist} is mandatory for {@code DISTINCT ON}
     *       to choose the right row; the outer ORDER BY then re-sorts the
     *       de-duplicated set by distance globally.</li>
     *   <li>The GIST index {@code service_areas_center_gix} (declared in
     *       {@code V7__create_service_areas.sql}) lets {@code ST_DWithin}
     *       short-circuit via bounding-box probe — sub-millisecond on the
     *       expected dataset size.</li>
     * </ul>
     */
    @Query(value = """
            SELECT closest.user_id        AS userId,
                   closest.id             AS serviceAreaId,
                   closest.dist           AS distanceMeters,
                   closest.radius_meters  AS radiusMeters
            FROM (
                SELECT DISTINCT ON (user_id)
                       id,
                       user_id,
                       radius_meters,
                       ST_Distance(
                           center,
                           ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
                       ) AS dist
                FROM service_areas
                WHERE service_category_id = :categoryId
                  AND ST_DWithin(
                          center,
                          ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                          radius_meters
                      )
                ORDER BY user_id,
                         ST_Distance(
                             center,
                             ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
                         )
            ) AS closest
            ORDER BY closest.dist
            LIMIT :limit
            """, nativeQuery = true)
    List<NearbyProviderProjection> findNearbyProviders(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("categoryId") long categoryId,
            @Param("limit") int limit);
}

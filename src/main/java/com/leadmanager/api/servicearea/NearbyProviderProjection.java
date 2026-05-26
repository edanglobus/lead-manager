package com.leadmanager.api.servicearea;

/**
 * Spring Data interface projection for one row of the "nearby providers"
 * spatial query.
 * <p>
 * Lives next to {@link ServiceAreaRepository} because it is purely the
 * shape Hibernate populates from the native query result set — never
 * leaves the persistence layer. The service tier translates each row
 * into a {@link NearbyProviderMatch} record so callers downstream
 * (HTTP, future CLI) depend on an immutable value, not on a Hibernate
 * proxy whose lifetime is bound to the transaction.
 * <p>
 * Getter names match the column aliases declared in the native query
 * ({@code userId}, {@code serviceAreaId}, {@code distanceMeters},
 * {@code radiusMeters}). Renaming either side without the other will
 * surface as {@code null} values at runtime — the integration test
 * catches that.
 */
public interface NearbyProviderProjection {

    Long getUserId();

    Long getServiceAreaId();

    Double getDistanceMeters();

    Integer getRadiusMeters();
}

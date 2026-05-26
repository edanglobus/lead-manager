package com.leadmanager.api.servicearea;

import org.locationtech.jts.geom.Point;

import com.leadmanager.api.common.audit.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A provider's working region for one trade: a circle defined by a center
 * point ({@link Point}, SRID 4326 WGS84) and a radius in meters.
 * <p>
 * Why the FKs are stored as raw {@code Long} ids (not {@code @ManyToOne}):
 * <ul>
 *   <li>Avoids the classic N+1 problem when listing many areas at once —
 *       callers fetch the related user/category explicitly via their own
 *       repository if they actually need the related entity.</li>
 *   <li>Matches the existing project pattern (see
 *       {@link com.leadmanager.api.user.RefreshToken}).</li>
 *   <li>Referential integrity is enforced at the database level via the
 *       FK constraints declared in {@code V7__create_service_areas.sql}.</li>
 * </ul>
 * <p>
 * The entity is read-mostly: the application creates a new row or deletes
 * an existing one but does not mutate fields in place (no "edit area" v1
 * — delete + recreate). Hence no setters; the {@code @Builder} is the only
 * way to construct one.
 */
@Entity
@Table(name = "service_areas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceArea extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "service_category_id", nullable = false)
    private Long serviceCategoryId;

    @Column(name = "center", columnDefinition = "geography(Point,4326)", nullable = false)
    private Point center;

    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters;

    @Builder
    private ServiceArea(Long userId, Long serviceCategoryId, Point center, Integer radiusMeters) {
        this.userId = userId;
        this.serviceCategoryId = serviceCategoryId;
        this.center = center;
        this.radiusMeters = radiusMeters;
    }
}

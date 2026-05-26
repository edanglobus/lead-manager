---
name: add-postgis-spatial-entity
description: Use when adding a JPA entity backed by a PostGIS spatial column (geography or geometry) to the Lead Manager backend. Covers the hibernate-spatial dependency, the SRID 4326 (WGS84) standard, the GIST index that makes ST_DWithin actually fast, the JTS (X, Y) = (longitude, latitude) reversal gotcha that has bitten every spatial codebase ever, and the radius-in-meters convention. Trigger when the user asks to add an entity with a location, a service area, a region, a geofence, or anywhere the schema needs lat/lng with spatial queries.
---

# Adding a spatial entity with PostGIS

## Prerequisites

PostGIS must already be enabled in the database. In Lead Manager that's V5 (`enable_postgis.sql`) + the `postgis/postgis:16-3.4-alpine` image in `docker-compose.yml`. If you're standing up a brand-new project, you need that first.

## Step 1 — pom dependency (one-time per project)

```xml
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-spatial</artifactId>
</dependency>
```

No version — Spring Boot BOM manages it in lock-step with `hibernate-core`. This artifact pulls in JTS (`org.locationtech.jts`) which is what the entity's `Point` field will be.

## Step 2 — the Flyway migration

```sql
CREATE TABLE service_areas (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    center         geography(Point, 4326) NOT NULL,
    radius_meters  INTEGER      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_service_areas_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT service_areas_radius_bounds CHECK (radius_meters BETWEEN 100 AND 200000)
);

CREATE INDEX service_areas_center_gix ON service_areas USING GIST (center);
```

Three things that matter:

1. **`geography(Point, 4326)`** — not `geometry`. Use **geography** when you want distances measured on a sphere in meters (the natural case for "find providers near me"). `geometry` measures in raw coordinate units and is only correct for tiny areas or after reprojection.
2. **SRID 4326** is WGS84 — the same coordinate system every consumer maps API hands the client. Using anything else means a reprojection at the boundary.
3. **GIST index** is mandatory. Without it `ST_DWithin` does a sequential scan. With it, sub-millisecond bounding-box probes. Name it `{table}_{col}_gix`.

## Step 3 — the entity

```java
@Entity
@Table(name = "service_areas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceArea extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "center", columnDefinition = "geography(Point,4326)", nullable = false)
    private Point center;

    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters;

    @Builder
    private ServiceArea(Long userId, Point center, Integer radiusMeters) {
        this.userId = userId;
        this.center = center;
        this.radiusMeters = radiusMeters;
    }
}
```

`Point` is `org.locationtech.jts.geom.Point`. The `columnDefinition` is required — without it Hibernate emits a generic `bytea` and `ddl-auto: validate` fails at boot.

## Step 4 — building a Point in the service tier

```java
private static final int SRID_WGS84 = 4326;
private static final GeometryFactory GEOMETRY_FACTORY =
        new GeometryFactory(new PrecisionModel(), SRID_WGS84);

private static Point buildPoint(double latitude, double longitude) {
    Point p = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    p.setSRID(SRID_WGS84);
    return p;
}
```

**THE gotcha:** JTS `Coordinate(x, y)` is `(longitude, latitude)` — X is east-west, Y is north-south. If you write `new Coordinate(latitude, longitude)` your data is rotated 90° and ST_DWithin returns nothing or everything depending on luck. Every project gets bitten by this once. Mitigations:

- Wrap the conversion in a private helper named `buildPoint(latitude, longitude)` so callers keep "lat first" naming locally.
- Write a unit test that **asserts the Y/X assignment** on a non-symmetric point (e.g. 32.08, 34.78) — symmetry hides the bug.

## Step 5 — Bean Validation on the input DTO

```java
@NotNull
@DecimalMin(value = "-90.0")  @DecimalMax(value = "90.0")
Double latitude,

@NotNull
@DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
Double longitude,

@NotNull
@Min(value = 100) @Max(value = 200000)
Integer radiusMeters
```

Validation here mirrors the DB CHECK constraint. The DB is defence in depth; if Bean Validation is bypassed (non-HTTP caller), you'd rather see a friendly 400 than a `DataIntegrityViolationException`.

## Step 6 — flatten Point back to lat/lng on the wire

Clients never deal in WKT or GeoJSON unless you deliberately commit to that contract. The response DTO has `double latitude` and `double longitude`. In the MapStruct mapper:

```java
@Mapping(target = "latitude",  expression = "java(area.getCenter().getY())")
@Mapping(target = "longitude", expression = "java(area.getCenter().getX())")
ServiceAreaResponse toResponse(ServiceArea area);
```

MapStruct cannot infer `Point.getY() → latitude` because JTS exposes them as anonymous (X, Y). Make the assignment explicit.

## Anti-patterns to refuse

- **`geometry` for "find within N km" queries.** Wrong unit (raw coordinates, not meters). Use `geography`.
- **`@Column` without `columnDefinition`.** Hibernate falls back to bytea; `ddl-auto: validate` fails at boot. Cryptic error.
- **Storing radius in km in the DB.** Mixed-unit codebase. Store meters (PostGIS native); convert at the UI layer if you want "5 km".
- **Skipping the GIST index.** Works locally with 100 rows; thermonuclear-slow at 100k.

## Related

- `add-flyway-migration` — table conventions, BaseEntity contract.
- `lead-manager-vertical-slice` — fits a spatial entity into the standard slice template.
- PostGIS docs: [geography type](https://postgis.net/docs/manual-3.4/PostGIS_FAQ.html#idm1454) (the geography vs geometry FAQ explains the unit choice).

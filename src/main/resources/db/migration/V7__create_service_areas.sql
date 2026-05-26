-- =====================================================================
-- V7__create_service_areas.sql
-- A provider's working region for a given trade: a circle defined by a
-- center point and a radius. Powers "find providers whose service area
-- covers this customer's location" queries.
--
-- Notes:
--   * `center` is geography(Point, 4326) — WGS84 lat/lng on a sphere.
--     PostGIS measures distances on `geography` in METERS, so the
--     spatial query path needs no unit conversion. SRID 4326 is the
--     same coordinate system every consumer-facing maps API (Google,
--     Apple, OSM) hands the client, so no reprojection at the boundary.
--   * `radius_meters` is bounded [100m, 200km]. 100m floors out
--     zero-radius pranks; 200km is wider than any realistic field-
--     service route in Israel and stops a provider from declaring
--     "I cover the whole country" to game search results.
--   * FK to users is ON DELETE CASCADE because areas are owned data —
--     a deleted user's areas have no meaning. FK to service_categories
--     is ON DELETE RESTRICT because categories are soft-deleted
--     (active=false) precisely so historical references never break.
--   * Two indexes: a B-tree on user_id for the "my areas" list view,
--     and a GIST index on center for the spatial ST_DWithin lookup.
--     The GIST index is what turns the geo query from a sequential
--     scan into a sub-millisecond bounding-box probe.
--   * No (user_id, service_category_id) uniqueness — a provider may
--     reasonably have multiple disjoint areas for the same trade
--     (e.g. a plumber covering Tel Aviv center AND a separate radius
--     around their home).
-- =====================================================================

CREATE TABLE service_areas (
    id                   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT       NOT NULL,
    service_category_id  BIGINT       NOT NULL,
    center               geography(Point, 4326) NOT NULL,
    radius_meters        INTEGER      NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_service_areas_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_service_areas_category
        FOREIGN KEY (service_category_id) REFERENCES service_categories(id) ON DELETE RESTRICT,
    CONSTRAINT service_areas_radius_bounds
        CHECK (radius_meters BETWEEN 100 AND 200000)
);

-- B-tree for the "list my areas" path (filters by user_id).
CREATE INDEX service_areas_user_ix
    ON service_areas (user_id);

-- GIST for spatial lookups (ST_DWithin against the customer's point).
CREATE INDEX service_areas_center_gix
    ON service_areas USING GIST (center);

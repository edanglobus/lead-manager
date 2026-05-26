package com.leadmanager.api.servicearea;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the spatial query
 * {@link ServiceAreaRepository#findNearbyProviders(double, double, long, int)}.
 * <p>
 * Why a real container (not mocks, not H2):
 * <ul>
 *   <li>{@code ST_DWithin} / {@code ST_Distance} are PostGIS-only.
 *       H2 has no equivalent.</li>
 *   <li>The {@code DISTINCT ON} + nested-ordering interaction with the
 *       GIST index is exactly the surface area a unit test would have
 *       to mock away — defeating the point of testing the query.</li>
 *   <li>The image is {@code postgis/postgis:16-3.4-alpine} (NOT the
 *       plain {@code postgres:16-alpine} the smoke test uses) because
 *       {@code V5__enable_postgis.sql} requires the extension package
 *       to be installed at the OS level.</li>
 * </ul>
 * <p>
 * {@link DataJpaTest} normally swaps in an embedded DB; {@code @AutoConfigureTestDatabase(replace = NONE)}
 * tells it to keep the {@code @ServiceConnection}-backed Postgres
 * datasource. {@code @ActiveProfiles("test")} reuses
 * {@code application-test.yml} (which keeps Flyway enabled and
 * Hibernate set to {@code validate}, so the migration is the schema
 * under test).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class ServiceAreaRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    // Reference coordinates — three points in central Tel Aviv area:
    //   QUERY ........ Dizengoff Square (32.0788, 34.7741)
    //   CLOSE_USER ... ~500m north (covers query with 5km radius)
    //   FAR_USER  .... ~3km east   (covers query with 5km radius)
    //   OUT_USER ..... ~30km away  (does NOT cover query with 5km radius)
    private static final double QUERY_LAT = 32.0788;
    private static final double QUERY_LNG = 34.7741;

    private static final long HVAC_CATEGORY_ID     = 7L;   // seeded in V6
    private static final long PLUMBING_CATEGORY_ID = 12L;  // seeded in V6

    @Autowired private ServiceAreaRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private long closeUserId;
    private long farUserId;
    private long outUserId;

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();
        // Service areas FK to users(id) ON CASCADE — wipe & reseed three
        // bare-minimum users so the spatial rows have something to point at.
        jdbc.update("DELETE FROM users");
        closeUserId = insertUser("close@test.io");
        farUserId   = insertUser("far@test.io");
        outUserId   = insertUser("out@test.io");
    }

    @Test
    void findNearbyProviders_returnsOnlyAreasCoveringTheQueryPoint_inSpecifiedCategory() {
        // CLOSE: ~500m north of query, 5km radius → covers query
        repository.saveAndFlush(area(closeUserId, HVAC_CATEGORY_ID, 32.0833, 34.7741, 5_000));
        // FAR: ~3km east of query, 5km radius → still covers
        repository.saveAndFlush(area(farUserId, HVAC_CATEGORY_ID, 32.0788, 34.8060, 5_000));
        // OUT: ~30km away, 5km radius → does NOT cover
        repository.saveAndFlush(area(outUserId, HVAC_CATEGORY_ID, 32.3000, 34.7741, 5_000));
        // WRONG TRADE: same close point but plumbing instead of HVAC
        repository.saveAndFlush(area(closeUserId, PLUMBING_CATEGORY_ID, 32.0833, 34.7741, 5_000));

        List<NearbyProviderProjection> results = repository.findNearbyProviders(
                QUERY_LAT, QUERY_LNG, HVAC_CATEGORY_ID, 25);

        assertThat(results)
                .extracting(NearbyProviderProjection::getUserId)
                .containsExactly(closeUserId, farUserId);

        // Distances are in meters and CLOSE must be < FAR.
        assertThat(results.get(0).getDistanceMeters())
                .isLessThan(results.get(1).getDistanceMeters());
        // Sanity: CLOSE is ~500m, FAR is ~3km — both within radius.
        assertThat(results.get(0).getDistanceMeters()).isBetween(300.0, 800.0);
        assertThat(results.get(1).getDistanceMeters()).isBetween(2_500.0, 3_500.0);
    }

    @Test
    void findNearbyProviders_deduplicatesByUser_returningClosestArea() {
        // Same provider, two overlapping HVAC areas. The closer one must
        // be the one returned (DISTINCT ON (user_id) + ORDER BY dist).
        ServiceArea nearer  = repository.saveAndFlush(area(closeUserId, HVAC_CATEGORY_ID, 32.0800, 34.7741, 5_000));
        ServiceArea farther = repository.saveAndFlush(area(closeUserId, HVAC_CATEGORY_ID, 32.0900, 34.7741, 5_000));

        List<NearbyProviderProjection> results = repository.findNearbyProviders(
                QUERY_LAT, QUERY_LNG, HVAC_CATEGORY_ID, 25);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getUserId()).isEqualTo(closeUserId);
        assertThat(results.get(0).getServiceAreaId()).isEqualTo(nearer.getId());
        assertThat(results.get(0).getServiceAreaId()).isNotEqualTo(farther.getId());
    }

    @Test
    void findNearbyProviders_respectsLimit() {
        repository.saveAndFlush(area(closeUserId, HVAC_CATEGORY_ID, 32.0833, 34.7741, 5_000));
        repository.saveAndFlush(area(farUserId,   HVAC_CATEGORY_ID, 32.0788, 34.8060, 5_000));

        List<NearbyProviderProjection> results = repository.findNearbyProviders(
                QUERY_LAT, QUERY_LNG, HVAC_CATEGORY_ID, 1);

        assertThat(results).hasSize(1);
        // The closer of the two wins.
        assertThat(results.get(0).getUserId()).isEqualTo(closeUserId);
    }

    @Test
    void findNearbyProviders_emptyWhenNoAreasCoverThePoint() {
        repository.saveAndFlush(area(outUserId, HVAC_CATEGORY_ID, 32.3000, 34.7741, 5_000));

        List<NearbyProviderProjection> results = repository.findNearbyProviders(
                QUERY_LAT, QUERY_LNG, HVAC_CATEGORY_ID, 25);

        assertThat(results).isEmpty();
    }

    // ---------- helpers ----------

    private long insertUser(String email) {
        // V3__create_users.sql columns: id (identity), email, password_hash,
        // display_name, plus audit columns with defaults.
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name)
                VALUES (?, ?, ?)
                RETURNING id
                """,
                Long.class,
                email,
                // 60-char bcrypt-shaped placeholder — schema check requires
                // length=60. Value is never used; nothing in this test logs in.
                "$2a$12$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "Test User " + email);
    }

    private static ServiceArea area(long userId, long categoryId,
                                    double lat, double lng, int radiusMeters) {
        Point p = FACTORY.createPoint(new Coordinate(lng, lat));
        p.setSRID(4326);
        return ServiceArea.builder()
                .userId(userId)
                .serviceCategoryId(categoryId)
                .center(p)
                .radiusMeters(radiusMeters)
                .build();
    }
}

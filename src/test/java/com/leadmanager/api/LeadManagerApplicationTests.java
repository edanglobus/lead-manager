package com.leadmanager.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: boots the full Spring context against a real Postgres container.
 * <p>
 * Why a real container instead of an embedded DB: per CLAUDE.md we mandate
 * Testcontainers over H2/mocks so that migrations, dialect quirks, and constraint
 * behavior match production. This single test guards the wiring of Spring Boot,
 * JPA, Flyway, and the datasource.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LeadManagerApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoads() {
        // Intentionally empty: success == Spring context started + Flyway migrations applied.
    }
}

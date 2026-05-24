package com.leadmanager.api.common.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Activates Spring Data JPA's auditing listeners so {@code @CreatedDate} and
 * {@code @LastModifiedDate} on {@link BaseEntity} populate automatically on
 * persist / update.
 * <p>
 * Lives in its own class (not on the main application) so future tests can
 * disable auditing in isolation if they need to assert raw timestamps.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}

package com.leadmanager.api.common.audit;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * Common base for every persistent entity in the system.
 * <p>
 * Why centralize:
 * <ul>
 *   <li>Identity strategy ({@code BIGINT IDENTITY}) is the project-wide default
 *       per the architecture plan; pinning it here prevents accidental drift
 *       to {@code SEQUENCE} or {@code AUTO}.</li>
 *   <li>Optimistic locking ({@link Version}) is mandatory on every entity to
 *       surface concurrent edits as {@code 409 Conflict} at the API rather
 *       than silently overwriting.</li>
 *   <li>{@code created_at} / {@code updated_at} are written by Spring Data JPA
 *       Auditing so domain code never has to touch them. The columns also
 *       carry SQL {@code DEFAULT now()} so direct SQL inserts (e.g. test
 *       fixtures, future Liquibase data migrations) stay valid.</li>
 * </ul>
 * <p>
 * Equality is intentionally id-based: two managed entities of the same type
 * with the same non-null id are equal. Unpersisted entities (id == null) are
 * only equal to themselves. This avoids the classic JPA bug where Lombok's
 * {@code @Data} folds mutable fields into {@code hashCode} and breaks Sets.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || !getClass().equals(other.getClass())) return false;
        BaseEntity that = (BaseEntity) other;
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        // Stable across the entity's lifecycle: pre-persist objects hash to
        // their class identity; post-persist objects hash to their id.
        return Objects.hashCode(getClass());
    }
}

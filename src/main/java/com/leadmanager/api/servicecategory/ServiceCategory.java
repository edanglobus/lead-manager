package com.leadmanager.api.servicecategory;

import com.leadmanager.api.common.audit.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A trade / service-line a provider can offer (e.g. HVAC, plumbing).
 * <p>
 * This is reference data: rows are created and updated exclusively by
 * Flyway migrations, never by application code. The entity therefore
 * exposes no setters and the package has no service-tier mutation API —
 * from the JVM's perspective it is immutable.
 * <p>
 * Why a table and not a Java enum:
 * <ul>
 *   <li>Display labels can be edited without redeploying the API.</li>
 *   <li>New trades can be added by a Flyway migration without a code
 *       release.</li>
 *   <li>Future per-region overrides (e.g. a trade valid only in Israel)
 *       become a row, not an enum branch.</li>
 * </ul>
 * {@code code} is the stable machine identifier other tables will
 * foreign-key into; {@code displayName} is the human-readable label
 * the UI renders.
 */
@Entity
@Table(name = "service_categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceCategory extends BaseEntity {

    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Builder
    private ServiceCategory(String code, String displayName, boolean active, int sortOrder) {
        this.code = code;
        this.displayName = displayName;
        this.active = active;
        this.sortOrder = sortOrder;
    }
}

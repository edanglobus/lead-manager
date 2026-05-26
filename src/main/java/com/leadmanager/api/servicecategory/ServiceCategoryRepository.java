package com.leadmanager.api.servicecategory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence port for {@link ServiceCategory}.
 * <p>
 * Categories are reference data — there are no mutating queries here and
 * none should be added. New trades and label edits ship as Flyway
 * migrations so they are versioned alongside the code that depends on them.
 */
@Repository
public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, Long> {

    /**
     * Returns active categories ordered for client display. Inactive rows
     * are filtered at the repository so callers can't accidentally surface
     * a deprecated trade through the public endpoint.
     */
    List<ServiceCategory> findAllByActiveTrueOrderBySortOrderAscDisplayNameAsc();
}

package com.leadmanager.api.servicecategory.web;

import java.util.List;

import org.mapstruct.Mapper;

import com.leadmanager.api.servicecategory.ServiceCategory;

/**
 * Converter from the {@code ServiceCategory} entity to its public HTTP view.
 * <p>
 * MapStruct generates the implementation at compile time; the pom configures
 * {@code unmappedTargetPolicy=ERROR}, so adding a field to
 * {@link ServiceCategoryResponse} without mapping it fails the build instead
 * of silently returning {@code null} at runtime.
 * <p>
 * Only an entity-to-response direction exists: there is no inbound mapping
 * because the public endpoint is read-only (categories are mutated via
 * Flyway migrations, not the API).
 */
@Mapper
public interface ServiceCategoryMapper {

    ServiceCategoryResponse toResponse(ServiceCategory category);

    List<ServiceCategoryResponse> toResponses(List<ServiceCategory> categories);
}

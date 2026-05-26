package com.leadmanager.api.servicecategory.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leadmanager.api.common.api.ApiVersion;
import com.leadmanager.api.servicecategory.ServiceCategoryRepository;

/**
 * HTTP-layer entry point for service-category reads.
 * <p>
 * Authentication is required (the path falls through to
 * {@code .anyRequest().authenticated()} in {@code SecurityConfig}); the
 * endpoint is read-only and the response is identical for every caller, but
 * exposing reference data to unauthenticated clients invites scraping and
 * leaks the catalog before signup is required by the rest of the product.
 * <p>
 * No service tier: there is no business logic between the controller and
 * the repository — the controller is a one-liner that sorts and shapes the
 * data. Adding a {@code ServiceCategoryService} would be process for its
 * own sake. The day we need to layer in caching, rate-limiting on a
 * per-tenant basis, or merging multiple sources, that's the day a service
 * tier earns its keep.
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/service-categories")
public class ServiceCategoryController {

    private final ServiceCategoryRepository repository;
    private final ServiceCategoryMapper mapper;

    public ServiceCategoryController(ServiceCategoryRepository repository,
                                     ServiceCategoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @GetMapping
    public List<ServiceCategoryResponse> list() {
        return mapper.toResponses(
                repository.findAllByActiveTrueOrderBySortOrderAscDisplayNameAsc());
    }
}

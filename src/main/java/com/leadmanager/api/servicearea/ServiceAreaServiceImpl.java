package com.leadmanager.api.servicearea;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.servicecategory.ServiceCategory;
import com.leadmanager.api.servicecategory.ServiceCategoryRepository;

@Service
public class ServiceAreaServiceImpl implements ServiceAreaService {

    /**
     * WGS84 spatial reference id — the same lat/lng system every consumer
     * maps API hands the client. PostGIS measures distances on
     * {@code geography(Point,4326)} in meters with no extra reprojection.
     */
    private static final int SRID_WGS84 = 4326;

    /**
     * JTS factory shared across all conversions. It is thread-safe by
     * documentation and immutable in practice, so a single static instance
     * is correct.
     */
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private final ServiceAreaRepository repository;
    private final ServiceCategoryRepository categoryRepository;

    public ServiceAreaServiceImpl(ServiceAreaRepository repository,
                                  ServiceCategoryRepository categoryRepository) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ServiceArea create(Long userId, ServiceAreaCreateCommand command) {
        // Pre-check that the referenced category exists AND is active. Without
        // this, an inactive category would be silently accepted (the FK
        // constraint only checks existence). The pre-check also gives the
        // caller a friendly 404 instead of a raw DataIntegrityViolation.
        ServiceCategory category = categoryRepository.findById(command.serviceCategoryId())
                .filter(ServiceCategory::isActive)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Service category not found"));

        ServiceArea area = ServiceArea.builder()
                .userId(userId)
                .serviceCategoryId(category.getId())
                .center(buildPoint(command.latitude(), command.longitude()))
                .radiusMeters(command.radiusMeters())
                .build();

        return repository.save(area);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceArea> listMine(Long userId) {
        return repository.findAllByUserIdOrderByIdDesc(userId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void delete(Long userId, Long areaId) {
        // Ownership-scoped lookup: if the area exists but belongs to a
        // different user, findByIdAndUserId returns empty just the same as
        // "id does not exist." Both surface as 404, never 403 — so the
        // response code can't be used to enumerate other users' area ids.
        ServiceArea area = repository.findByIdAndUserId(areaId, userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Service area not found"));
        repository.delete(area);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NearbyProviderMatch> findNearbyProviders(NearbyProviderQuery query) {
        return repository.findNearbyProviders(
                        query.latitude(),
                        query.longitude(),
                        query.serviceCategoryId(),
                        query.limit())
                .stream()
                .map(row -> new NearbyProviderMatch(
                        row.getUserId(),
                        row.getServiceAreaId(),
                        row.getDistanceMeters(),
                        row.getRadiusMeters()))
                .toList();
    }

    /**
     * JTS {@link Point} uses (X, Y) = (longitude, latitude). Reversing them
     * is one of the classic geo bugs; the wrapper method centralises the
     * convention so callers can keep "lat first" naming locally.
     */
    private static Point buildPoint(double latitude, double longitude) {
        Point p = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(SRID_WGS84);
        return p;
    }
}

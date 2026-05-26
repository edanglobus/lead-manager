package com.leadmanager.api.servicearea.web;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.leadmanager.api.common.api.ApiVersion;
import com.leadmanager.api.servicearea.NearbyProviderMatch;
import com.leadmanager.api.servicearea.NearbyProviderQuery;
import com.leadmanager.api.servicearea.ServiceAreaService;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Public-ish search endpoint that surfaces providers whose service area
 * covers a customer-supplied point in a given trade.
 * <p>
 * Sits under {@code /providers} rather than {@code /service-areas} because
 * from the caller's perspective the resource being searched is "providers
 * who can serve me," not "service-area rows." The data source happens to
 * be {@code service_areas}, but that's an implementation detail.
 * <p>
 * Authentication is still required (the global {@code SecurityConfig}
 * locks down anything not on the allowlist) — there's no business reason
 * to expose this anonymously, and requiring a token gives us a JWT
 * subject claim for the future Bucket4j rate limiter (per-user, not per-IP).
 * <p>
 * Class-level {@link Validated} activates Jakarta Bean Validation on the
 * individual query params; failures throw {@code ConstraintViolationException}
 * which {@code GlobalExceptionHandler} converts to RFC 7807 400.
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/providers")
@Validated
public class NearbyProvidersController {

    /**
     * Hard cap on the {@code limit} query param. Keeps the wire payload
     * small (mobile clients on cellular) and the DB scan bounded even
     * if a caller asks for everything.
     */
    private static final int MAX_LIMIT = 100;

    /**
     * Default {@code limit} when the caller omits the parameter. Big
     * enough to fill a phone screen of cards comfortably; small enough
     * that the response stays well under a TCP MTU.
     */
    private static final int DEFAULT_LIMIT = 25;

    private final ServiceAreaService service;

    public NearbyProvidersController(ServiceAreaService service) {
        this.service = service;
    }

    /**
     * Lists providers covering {@code (lat, lng)} in the given trade,
     * closest first.
     * <p>
     * Responses:
     * <ul>
     *   <li>{@code 200 OK} with a (possibly empty) JSON array of
     *       {@link NearbyProviderResponse}.</li>
     *   <li>{@code 400 Bad Request} (RFC 7807,
     *       {@code code=VALIDATION_FAILED}) when any param is missing
     *       or out of range.</li>
     *   <li>{@code 401 Unauthorized} when the bearer token is missing
     *       or invalid (handled by the filter chain, not this method).</li>
     * </ul>
     */
    @GetMapping("/nearby")
    public List<NearbyProviderResponse> findNearby(
            @RequestParam("lat")
            @NotNull(message = "lat is required")
            @DecimalMin(value = "-90.0", message = "lat must be >= -90")
            @DecimalMax(value = "90.0",  message = "lat must be <= 90")
            Double lat,

            @RequestParam("lng")
            @NotNull(message = "lng is required")
            @DecimalMin(value = "-180.0", message = "lng must be >= -180")
            @DecimalMax(value = "180.0",  message = "lng must be <= 180")
            Double lng,

            @RequestParam("categoryId")
            @NotNull(message = "categoryId is required")
            @Positive(message = "categoryId must be positive")
            Long categoryId,

            @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
            @Min(value = 1,         message = "limit must be >= 1")
            @Max(value = MAX_LIMIT, message = "limit must be <= " + MAX_LIMIT)
            int limit) {

        NearbyProviderQuery query = new NearbyProviderQuery(lat, lng, categoryId, limit);

        return service.findNearbyProviders(query).stream()
                .map(NearbyProvidersController::toResponse)
                .toList();
    }

    private static NearbyProviderResponse toResponse(NearbyProviderMatch match) {
        return new NearbyProviderResponse(
                match.userId(),
                match.serviceAreaId(),
                match.distanceMeters(),
                match.radiusMeters());
    }
}

package com.leadmanager.api.servicearea.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.leadmanager.api.common.api.ApiVersion;
import com.leadmanager.api.common.security.AuthenticatedUser;
import com.leadmanager.api.servicearea.ServiceArea;
import com.leadmanager.api.servicearea.ServiceAreaService;

import jakarta.validation.Valid;

/**
 * HTTP-layer entry point for service-area writes by the authenticated user.
 * <p>
 * Sub-step 3.2 ships {@code POST /service-areas}. 3.3 will add
 * {@code GET /service-areas/me} and {@code DELETE /service-areas/{id}}.
 * <p>
 * Controller contract per {@code CLAUDE.md}: parse, convert, delegate,
 * respond. Ownership is enforced by the service tier — the controller only
 * supplies the authenticated user's id and trusts the service to act on
 * behalf of that user.
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/service-areas")
public class ServiceAreaController {

    private final ServiceAreaService service;
    private final ServiceAreaMapper mapper;

    public ServiceAreaController(ServiceAreaService service, ServiceAreaMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /**
     * Creates a service area for the authenticated provider.
     * <p>
     * Responses:
     * <ul>
     *   <li>{@code 201 Created} with a {@code Location} header pointing at
     *       the future {@code GET /service-areas/{id}} resource and a
     *       {@link ServiceAreaResponse} body on success.</li>
     *   <li>{@code 400 Bad Request} (RFC 7807) when Bean Validation fails.</li>
     *   <li>{@code 401 Unauthorized} when the bearer token is missing or
     *       invalid (handled by the filter chain, not this method).</li>
     *   <li>{@code 404 Not Found} (RFC 7807, {@code code=RESOURCE_NOT_FOUND})
     *       when the referenced category does not exist or is inactive.</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<ServiceAreaResponse> create(
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody ServiceAreaRequest request) {

        ServiceArea saved = service.create(me.id(), mapper.toCommand(request));
        ServiceAreaResponse body = mapper.toResponse(saved);

        return ResponseEntity
                .created(UriComponentsBuilder
                        .fromPath(ApiVersion.V1 + "/service-areas/{id}")
                        .buildAndExpand(saved.getId())
                        .toUri())
                .body(body);
    }

    /**
     * Lists the authenticated provider's own service areas, newest first.
     * <p>
     * Responses:
     * <ul>
     *   <li>{@code 200 OK} with a (possibly empty) JSON array of
     *       {@link ServiceAreaResponse}.</li>
     *   <li>{@code 401 Unauthorized} when the bearer token is missing or
     *       invalid (handled by the filter chain).</li>
     * </ul>
     */
    @GetMapping("/me")
    public List<ServiceAreaResponse> listMine(@AuthenticationPrincipal AuthenticatedUser me) {
        return mapper.toResponses(service.listMine(me.id()));
    }

    /**
     * Deletes one of the authenticated provider's areas.
     * <p>
     * Responses:
     * <ul>
     *   <li>{@code 204 No Content} on success.</li>
     *   <li>{@code 401 Unauthorized} when the bearer token is missing or
     *       invalid.</li>
     *   <li>{@code 404 Not Found} (RFC 7807, {@code code=RESOURCE_NOT_FOUND})
     *       when the id does not exist OR belongs to a different user —
     *       the two cases are merged on purpose so the response code
     *       cannot be used to enumerate other users' area ids.</li>
     * </ul>
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me,
                       @PathVariable Long id) {
        service.delete(me.id(), id);
    }
}

package com.leadmanager.api.servicearea.web;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.leadmanager.api.servicearea.ServiceArea;
import com.leadmanager.api.servicearea.ServiceAreaCreateCommand;

/**
 * Bidirectional converter between the HTTP layer and the service layer for
 * the {@code ServiceArea} aggregate.
 * <p>
 * MapStruct generates the implementation at compile time. The {@code center}
 * field is unwound into {@code latitude} / {@code longitude} via explicit
 * Java expressions — MapStruct cannot infer that {@code Point.getY()} is
 * "latitude" and {@code Point.getX()} is "longitude" since the JTS API
 * exposes them as anonymous (X, Y) coordinates.
 */
@Mapper
public interface ServiceAreaMapper {

    /** Inbound HTTP DTO → service-layer command. Field names match 1:1. */
    ServiceAreaCreateCommand toCommand(ServiceAreaRequest request);

    /**
     * Entity → public response. Point components are extracted explicitly
     * — see the class JavaDoc for the (X, Y) vs (lat, lng) rationale.
     */
    @Mapping(target = "latitude",  expression = "java(area.getCenter().getY())")
    @Mapping(target = "longitude", expression = "java(area.getCenter().getX())")
    ServiceAreaResponse toResponse(ServiceArea area);

    List<ServiceAreaResponse> toResponses(List<ServiceArea> areas);
}

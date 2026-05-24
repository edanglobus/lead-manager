package com.leadmanager.api.user.web;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.leadmanager.api.user.RegisterUserCommand;
import com.leadmanager.api.user.User;

/**
 * Bidirectional converter between the HTTP layer and the service layer for
 * the {@code User} aggregate.
 * <p>
 * MapStruct generates the implementation at compile time. After running
 * {@code ./mvnw clean compile} you can read the generated source at
 * {@code target/generated-sources/annotations/com/leadmanager/api/user/web/UserMapperImpl.java}
 * — useful for verifying that no field has been silently dropped.
 * <p>
 * Why mappers (rather than the controller doing it inline):
 * <ul>
 *   <li><b>SoC.</b> Keeps the controller method body small enough to read
 *       at a glance; the mapping rules live in one auditable place.</li>
 *   <li><b>Compile-time safety.</b> The pom configures
 *       {@code mapstruct.unmappedTargetPolicy=ERROR}, so adding a new field
 *       to {@link UserResponse} without mapping it fails the build instead
 *       of silently returning {@code null} at runtime.</li>
 *   <li><b>Future masking.</b> When the blind-transfer slice lands, the
 *       {@code JobMapper} will take a {@code MaskingContext} parameter; doing
 *       it in a mapper from the start keeps the pattern consistent.</li>
 * </ul>
 */
@Mapper
public interface UserMapper {

    /**
     * HTTP request DTO → service-layer command. Field names match 1:1 except
     * {@code password} → {@code rawPassword}; the explicit {@link Mapping}
     * documents the rename so MapStruct's strict
     * {@code unmappedTargetPolicy=ERROR} stays happy and a future reader
     * sees why the names differ (HTTP field is "password" for ergonomics;
     * the command spells out "raw" so the service contract makes the
     * pre-hash state obvious).
     */
    @Mapping(source = "password", target = "rawPassword")
    RegisterUserCommand toCommand(RegisterUserRequest request);

    /**
     * Domain entity → public response. {@code passwordHash} and
     * {@code version} are deliberately absent from {@link UserResponse} and
     * therefore NEVER reach the wire.
     */
    UserResponse toResponse(User user);
}

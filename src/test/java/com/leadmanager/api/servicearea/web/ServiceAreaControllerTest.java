package com.leadmanager.api.servicearea.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.common.exception.GlobalExceptionHandler;
import com.leadmanager.api.common.security.JwtAuthFilter;
import com.leadmanager.api.common.security.JwtAuthenticationEntryPoint;
import com.leadmanager.api.common.security.JwtService;
import com.leadmanager.api.common.security.SecurityConfig;
import com.leadmanager.api.servicearea.ServiceArea;
import com.leadmanager.api.servicearea.ServiceAreaCreateCommand;
import com.leadmanager.api.servicearea.ServiceAreaService;

/**
 * Web-layer test for {@link ServiceAreaController}. Imports the REAL
 * {@link SecurityConfig}, {@link JwtAuthFilter}, and
 * {@link JwtAuthenticationEntryPoint} so the test exercises the actual
 * filter chain — only {@link JwtService} and {@link ServiceAreaService}
 * are mocked.
 */
@WebMvcTest(ServiceAreaController.class)
@Import({
        ServiceAreaMapperImpl.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthFilter.class,
        JwtAuthenticationEntryPoint.class
})
class ServiceAreaControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtService jwtService;
    @MockBean private ServiceAreaService serviceAreaService;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final long PRINCIPAL_USER_ID = 7L;
    private static final String ENDPOINT = "/api/v1/service-areas";

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void create_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type",
                        Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void create_returns400_whenRadiusMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        String bodyMissingRadius = """
                {
                    "serviceCategoryId": 12,
                    "latitude": 32.08,
                    "longitude": 34.78
                }
                """;

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyMissingRadius))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    void create_returns404_whenServiceReportsCategoryMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(serviceAreaService.create(eq(PRINCIPAL_USER_ID), any(ServiceAreaCreateCommand.class)))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Service category not found"));

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    void listMine_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(get(ENDPOINT + "/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void listMine_returns200_emptyArray_whenUserHasNoAreas() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(serviceAreaService.listMine(PRINCIPAL_USER_ID)).thenReturn(List.of());

        mockMvc.perform(get(ENDPOINT + "/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listMine_returns200_withMappedAreas() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        Point centerA = FACTORY.createPoint(new Coordinate(34.78, 32.08));
        centerA.setSRID(4326);
        ServiceArea a = ServiceArea.builder()
                .userId(PRINCIPAL_USER_ID).serviceCategoryId(12L)
                .center(centerA).radiusMeters(5000).build();
        setField(a, "id", 99L);
        setField(a, "createdAt", Instant.parse("2026-05-26T13:34:56Z"));

        when(serviceAreaService.listMine(PRINCIPAL_USER_ID)).thenReturn(List.of(a));

        mockMvc.perform(get(ENDPOINT + "/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].latitude").value(32.08))
                .andExpect(jsonPath("$[0].longitude").value(34.78))
                .andExpect(jsonPath("$[0].radiusMeters").value(5000));
    }

    @Test
    void delete_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(delete(ENDPOINT + "/99"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void delete_returns204_andInvokesService_whenOwned() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        mockMvc.perform(delete(ENDPOINT + "/99")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNoContent());

        verify(serviceAreaService).delete(PRINCIPAL_USER_ID, 99L);
    }

    @Test
    void delete_returns404_whenServiceReportsNotFoundOrNotOwned() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        doThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Service area not found"))
                .when(serviceAreaService).delete(PRINCIPAL_USER_ID, 999L);

        mockMvc.perform(delete(ENDPOINT + "/999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    void create_returns201_andBody_andLocationHeader_whenValid() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        Point center = FACTORY.createPoint(new Coordinate(34.78, 32.08));
        center.setSRID(4326);
        ServiceArea saved = ServiceArea.builder()
                .userId(PRINCIPAL_USER_ID)
                .serviceCategoryId(12L)
                .center(center)
                .radiusMeters(5000)
                .build();
        setField(saved, "id", 99L);
        setField(saved, "createdAt", Instant.parse("2026-05-26T13:34:56Z"));

        when(serviceAreaService.create(eq(PRINCIPAL_USER_ID), any(ServiceAreaCreateCommand.class)))
                .thenReturn(saved);

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        Matchers.endsWith("/api/v1/service-areas/99")))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.serviceCategoryId").value(12))
                .andExpect(jsonPath("$.latitude").value(32.08))
                .andExpect(jsonPath("$.longitude").value(34.78))
                .andExpect(jsonPath("$.radiusMeters").value(5000));
    }

    // ---------- helpers ----------

    private static String validBody() {
        return """
                {
                    "serviceCategoryId": 12,
                    "latitude": 32.08,
                    "longitude": 34.78,
                    "radiusMeters": 5000
                }
                """;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not set " + name, e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}

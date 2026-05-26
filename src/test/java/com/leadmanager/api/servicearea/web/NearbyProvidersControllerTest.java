package com.leadmanager.api.servicearea.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.common.exception.GlobalExceptionHandler;
import com.leadmanager.api.common.security.JwtAuthFilter;
import com.leadmanager.api.common.security.JwtAuthenticationEntryPoint;
import com.leadmanager.api.common.security.JwtService;
import com.leadmanager.api.common.security.SecurityConfig;
import com.leadmanager.api.servicearea.NearbyProviderMatch;
import com.leadmanager.api.servicearea.NearbyProviderQuery;
import com.leadmanager.api.servicearea.ServiceAreaService;

/**
 * Web-layer test for {@link NearbyProvidersController}. Imports the REAL
 * {@link SecurityConfig}, {@link JwtAuthFilter}, and
 * {@link JwtAuthenticationEntryPoint} so the test exercises the actual
 * filter chain — only {@link JwtService} and {@link ServiceAreaService}
 * are mocked.
 */
@WebMvcTest(NearbyProvidersController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthFilter.class,
        JwtAuthenticationEntryPoint.class
})
class NearbyProvidersControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtService jwtService;
    @MockBean private ServiceAreaService serviceAreaService;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final long PRINCIPAL_USER_ID = 7L;
    private static final String ENDPOINT = "/api/v1/providers/nearby";

    @Test
    void nearby_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("lat", "32.08")
                        .param("lng", "34.78")
                        .param("categoryId", "12"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type",
                        Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));

        verifyNoInteractions(serviceAreaService);
    }

    @Test
    void nearby_returns400_whenLatMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lng", "34.78")
                        .param("categoryId", "12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(serviceAreaService);
    }

    @Test
    void nearby_returns400_whenLatOutOfRange() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lat", "120.0")
                        .param("lng", "34.78")
                        .param("categoryId", "12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("lat")));

        verifyNoInteractions(serviceAreaService);
    }

    @Test
    void nearby_returns400_whenLngOutOfRange() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lat", "32.08")
                        .param("lng", "200.0")
                        .param("categoryId", "12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(serviceAreaService);
    }

    @Test
    void nearby_returns400_whenCategoryIdNotPositive() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lat", "32.08")
                        .param("lng", "34.78")
                        .param("categoryId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(serviceAreaService);
    }

    @Test
    void nearby_returns400_whenLimitAboveMax() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lat", "32.08")
                        .param("lng", "34.78")
                        .param("categoryId", "12")
                        .param("limit", "9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(serviceAreaService);
    }

    @Test
    void nearby_returns200_withMappedResults_andDefaultLimit() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        // Two providers, A closer than B. Default limit (25) when not supplied.
        when(serviceAreaService.findNearbyProviders(any(NearbyProviderQuery.class)))
                .thenReturn(List.of(
                        new NearbyProviderMatch(42L, 1001L, 350.0,  5000),
                        new NearbyProviderMatch(99L, 2002L, 1800.5, 8000)));

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lat", "32.08")
                        .param("lng", "34.78")
                        .param("categoryId", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(42))
                .andExpect(jsonPath("$[0].serviceAreaId").value(1001))
                .andExpect(jsonPath("$[0].distanceMeters").value(350.0))
                .andExpect(jsonPath("$[0].radiusMeters").value(5000))
                .andExpect(jsonPath("$[1].userId").value(99))
                .andExpect(jsonPath("$[1].distanceMeters").value(1800.5));

        verify(serviceAreaService).findNearbyProviders(argThat(q ->
                q.latitude() == 32.08
                        && q.longitude() == 34.78
                        && q.serviceCategoryId() == 12L
                        && q.limit() == 25));
    }

    @Test
    void nearby_returns200_emptyArray_whenServiceFindsNothing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(serviceAreaService.findNearbyProviders(any(NearbyProviderQuery.class)))
                .thenReturn(List.of());

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lat", "32.08")
                        .param("lng", "34.78")
                        .param("categoryId", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nearby_passesExplicitLimitThroughToService() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(serviceAreaService.findNearbyProviders(any(NearbyProviderQuery.class)))
                .thenReturn(List.of());

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .param("lat", "32.08")
                        .param("lng", "34.78")
                        .param("categoryId", "12")
                        .param("limit", "5"))
                .andExpect(status().isOk());

        verify(serviceAreaService).findNearbyProviders(argThat(q -> q.limit() == 5));
    }
}

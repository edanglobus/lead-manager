package com.leadmanager.api.servicecategory.web;

import static org.mockito.ArgumentMatchers.eq;
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
import com.leadmanager.api.common.security.JwtService.InvalidTokenException;
import com.leadmanager.api.common.security.SecurityConfig;
import com.leadmanager.api.servicecategory.ServiceCategory;
import com.leadmanager.api.servicecategory.ServiceCategoryRepository;

/**
 * Web-layer test for {@link ServiceCategoryController}. Imports the REAL
 * {@link SecurityConfig}, {@link JwtAuthFilter}, and
 * {@link JwtAuthenticationEntryPoint} so the test exercises the actual
 * filter chain — only {@link JwtService} and {@link ServiceCategoryRepository}
 * are mocked.
 */
@WebMvcTest(ServiceCategoryController.class)
@Import({
        ServiceCategoryMapperImpl.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthFilter.class,
        JwtAuthenticationEntryPoint.class
})
class ServiceCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ServiceCategoryRepository repository;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String ENDPOINT = "/api/v1/service-categories";

    @Test
    void list_returns401_problemDetail_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type",
                        Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()))
                .andExpect(jsonPath("$.type").value(ErrorCode.UNAUTHENTICATED.typeUri()))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void list_returns401_problemDetail_whenTokenIsInvalid() throws Exception {
        when(jwtService.parseAccessToken(eq("bogus")))
                .thenThrow(new InvalidTokenException("invalid token", new RuntimeException()));

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer bogus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void list_returns200_andSortedCategories_whenTokenIsValid() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(42L);

        ServiceCategory hvac = ServiceCategory.builder()
                .code("hvac").displayName("HVAC").active(true).sortOrder(70).build();
        ServiceCategory plumbing = ServiceCategory.builder()
                .code("plumbing").displayName("Plumbing").active(true).sortOrder(120).build();
        setField(hvac, "id", 7L);
        setField(plumbing, "id", 12L);

        when(repository.findAllByActiveTrueOrderBySortOrderAscDisplayNameAsc())
                .thenReturn(List.of(hvac, plumbing));

        mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].code").value("hvac"))
                .andExpect(jsonPath("$[0].displayName").value("HVAC"))
                .andExpect(jsonPath("$[0].active").doesNotExist())
                .andExpect(jsonPath("$[0].sortOrder").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(12))
                .andExpect(jsonPath("$[1].code").value("plumbing"))
                .andExpect(jsonPath("$[1].displayName").value("Plumbing"));
    }

    // ---------- helpers ----------

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
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}

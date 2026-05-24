package com.leadmanager.api.user.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

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
import com.leadmanager.api.user.User;
import com.leadmanager.api.user.UserRepository;

/**
 * Web-layer test for {@link UserController}. Imports the REAL
 * {@link SecurityConfig}, {@link JwtAuthFilter}, and
 * {@link JwtAuthenticationEntryPoint} so the test exercises the actual
 * filter chain — only {@link JwtService} and {@link UserRepository} are
 * mocked.
 * <p>
 * What this proves:
 * <ul>
 *   <li>No {@code Authorization} header → {@code 401} RFC 7807.</li>
 *   <li>Malformed token → {@code 401} RFC 7807 (the filter clears context;
 *       the entry point fires).</li>
 *   <li>Valid token → {@code 200} + {@link UserResponse}.</li>
 *   <li>Valid token but user no longer in DB → {@code 404 RESOURCE_NOT_FOUND}.</li>
 * </ul>
 */
@WebMvcTest(UserController.class)
@Import({
        UserMapperImpl.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthFilter.class,
        JwtAuthenticationEntryPoint.class
})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    private static final String VALID_TOKEN = "valid.jwt.token";

    @Test
    void getMe_returns401_problemDetail_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type",
                        Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()))
                .andExpect(jsonPath("$.type").value(ErrorCode.UNAUTHENTICATED.typeUri()))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void getMe_returns401_problemDetail_whenTokenIsInvalid() throws Exception {
        when(jwtService.parseAccessToken(eq("bogus")))
                .thenThrow(new InvalidTokenException("invalid token", new RuntimeException()));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer bogus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void getMe_returns200_andUserBody_whenTokenIsValid() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(7L);

        User stub = User.builder()
                .email("seven@example.com")
                .passwordHash("$2a$12$placeholderhash")
                .displayName("Lucky Seven")
                .phone(null)
                .build();
        setField(stub, "id", 7L);
        setField(stub, "createdAt", Instant.parse("2026-05-24T12:34:56Z"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(stub));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.email").value("seven@example.com"))
                .andExpect(jsonPath("$.displayName").value("Lucky Seven"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void getMe_returns404_problemDetail_whenUserDeletedAfterTokenIssued() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(999L);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
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

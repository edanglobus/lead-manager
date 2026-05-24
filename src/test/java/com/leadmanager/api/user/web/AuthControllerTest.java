package com.leadmanager.api.user.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.common.exception.GlobalExceptionHandler;
import com.leadmanager.api.common.security.JwtAuthFilter;
import com.leadmanager.api.common.security.JwtAuthenticationEntryPoint;
import com.leadmanager.api.common.security.JwtService;
import com.leadmanager.api.common.security.SecurityConfig;
import com.leadmanager.api.user.AuthService;
import com.leadmanager.api.user.LoginCommand;
import com.leadmanager.api.user.LoginResult;
import com.leadmanager.api.user.RegisterUserCommand;
import com.leadmanager.api.user.User;
import com.leadmanager.api.user.UserService;

/**
 * Web-layer test for {@link AuthController}. {@code @WebMvcTest} starts a
 * sliced Spring context that contains only the MVC infrastructure — no JPA,
 * no datasource, no Docker. {@link UserService} is mocked with
 * {@code @MockBean} and the {@link UserMapper} is the real MapStruct-generated
 * implementation (so the test would catch a mis-renamed field).
 * <p>
 * What we deliberately verify here:
 * <ul>
 *   <li>Happy path: 201, correct {@code Location}, the response body shape,
 *       and the {@link RegisterUserCommand} that reaches the service.</li>
 *   <li>Bean Validation: a bad payload returns 400 with an RFC 7807 body
 *       and the service is NEVER called.</li>
 *   <li>{@code EMAIL_TAKEN}: the service throwing {@link ApiException}
 *       surfaces as 409 with the right {@code code} and {@code type}.</li>
 * </ul>
 */
@WebMvcTest(AuthController.class)
@Import({
        UserMapperImpl.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthFilter.class,
        JwtAuthenticationEntryPoint.class
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private AuthService authService;

    // SecurityConfig pulls in JwtAuthFilter, which depends on JwtService.
    // /auth/register and /auth/login are permitAll so the filter never needs
    // to do anything here, but the bean still has to exist for the context
    // to start.
    @MockBean
    private JwtService jwtService;

    @Test
    void register_returns201_andLocationHeader_onHappyPath() throws Exception {
        User stub = User.builder()
                .email("Alice@Example.com")
                .passwordHash("$2a$12$xxxxxxxxxxxxxxxxxxxxxx")
                .displayName("Alice")
                .phone("+972-50-1234567")
                .build();
        // BaseEntity#id and createdAt are normally set by JPA; for a pure
        // web-slice test we cheat them in via reflection so the response
        // has the values we want to assert on.
        setField(stub, "id", 42L);
        setField(stub, "createdAt", Instant.parse("2026-05-24T10:00:00Z"));
        when(userService.register(any(RegisterUserCommand.class))).thenReturn(stub);

        mockMvc.perform(jsonPost("""
                {
                  "email":       "Alice@Example.com",
                  "password":    "correcthorsebatterystaple",
                  "displayName": "Alice",
                  "phone":       "+972-50-1234567"
                }
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("Alice@Example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.phone").value("+972-50-1234567"))
                .andExpect(jsonPath("$.createdAt").value("2026-05-24T10:00:00Z"))
                // passwordHash must NEVER appear in the response.
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        verify(userService).register(any(RegisterUserCommand.class));
    }

    @Test
    void register_returns400_problemDetail_whenEmailMissing() throws Exception {
        mockMvc.perform(jsonPost("""
                {
                  "password":    "correcthorsebatterystaple",
                  "displayName": "Alice"
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists());

        verifyNoInteractions(userService);
    }

    @Test
    void register_returns400_problemDetail_whenPasswordTooShort() throws Exception {
        mockMvc.perform(jsonPost("""
                {
                  "email":       "alice@example.com",
                  "password":    "short",
                  "displayName": "Alice"
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='password')]").exists());

        verifyNoInteractions(userService);
    }

    @Test
    void register_returns409_problemDetail_onEmailTaken() throws Exception {
        when(userService.register(any(RegisterUserCommand.class)))
                .thenThrow(new ApiException(ErrorCode.EMAIL_TAKEN, "Email is already registered"));

        mockMvc.perform(jsonPost("""
                {
                  "email":       "alice@example.com",
                  "password":    "correcthorsebatterystaple",
                  "displayName": "Alice"
                }
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_TAKEN.name()))
                .andExpect(jsonPath("$.type").value(ErrorCode.EMAIL_TAKEN.typeUri()))
                .andExpect(jsonPath("$.status").value(409));
    }

    // ---------- /login tests ----------

    @Test
    void login_returns200_andTokenBody_onHappyPath() throws Exception {
        when(authService.login(any(LoginCommand.class)))
                .thenReturn(new LoginResult("issued.jwt.token", 900L, "refresh-plaintext", 42L));

        mockMvc.perform(jsonPost("/api/v1/auth/login", """
                {
                  "email":    "alice@example.com",
                  "password": "correcthorsebatterystaple"
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("issued.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").value("refresh-plaintext"))
                // The plaintext password must NEVER appear in the response.
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authService).login(any(LoginCommand.class));
    }

    // ---------- /refresh tests ----------

    @Test
    void refresh_returns200_andRotatedPair_onHappyPath() throws Exception {
        when(authService.refresh("old-refresh"))
                .thenReturn(new LoginResult("new.access.token", 900L, "new-refresh", 42L));

        mockMvc.perform(jsonPost("/api/v1/auth/refresh", """
                {
                  "refreshToken": "old-refresh"
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void refresh_returns400_whenBodyMissingField() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/refresh", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[?(@.field=='refreshToken')]").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void refresh_returns401_onInvalidRefreshToken() throws Exception {
        when(authService.refresh("expired-or-bogus"))
                .thenThrow(new ApiException(
                        ErrorCode.INVALID_REFRESH_TOKEN,
                        "Refresh token is invalid or expired"));

        mockMvc.perform(jsonPost("/api/v1/auth/refresh", """
                {
                  "refreshToken": "expired-or-bogus"
                }
                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REFRESH_TOKEN.name()))
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REFRESH_TOKEN.typeUri()))
                .andExpect(jsonPath("$.status").value(401));
    }

    // ---------- /logout tests ----------

    @Test
    void logout_returns204_andCallsService_onValidToken() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/logout", """
                {
                  "refreshToken": "any-token"
                }
                """))
                .andExpect(status().isNoContent());

        verify(authService).logout("any-token");
    }

    @Test
    void logout_returns204_evenForBogusToken_byDesign() throws Exception {
        // Idempotent: the service silently no-ops on unknown tokens so we
        // cannot enumerate valid refresh tokens via response codes.
        mockMvc.perform(jsonPost("/api/v1/auth/logout", """
                {
                  "refreshToken": "totally-bogus"
                }
                """))
                .andExpect(status().isNoContent());

        verify(authService).logout("totally-bogus");
    }

    @Test
    void logout_returns400_whenBodyMissingField() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/logout", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[?(@.field=='refreshToken')]").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void login_returns400_problemDetail_whenEmailMalformed() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/login", """
                {
                  "email":    "not-an-email",
                  "password": "any-password"
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void login_returns401_problemDetail_onInvalidCredentials() throws Exception {
        when(authService.login(any(LoginCommand.class)))
                .thenThrow(new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));

        mockMvc.perform(jsonPost("/api/v1/auth/login", """
                {
                  "email":    "alice@example.com",
                  "password": "wrongpassword"
                }
                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_CREDENTIALS.name()))
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_CREDENTIALS.typeUri()))
                .andExpect(jsonPath("$.status").value(401));
    }

    // ---------- helpers ----------

    /** Shorthand for /auth/register, the original path tested by this class. */
    private MockHttpServletRequestBuilder jsonPost(String body) {
        return jsonPost("/api/v1/auth/register", body);
    }

    private MockHttpServletRequestBuilder jsonPost(String path, String body) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}

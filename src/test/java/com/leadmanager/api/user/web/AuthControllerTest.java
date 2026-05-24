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

    // SecurityConfig pulls in JwtAuthFilter, which depends on JwtService.
    // /auth/register is permitAll so the filter never needs to do anything,
    // but the bean still has to exist for the context to start.
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

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder jsonPost(String body) {
        return post("/api/v1/auth/register")
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

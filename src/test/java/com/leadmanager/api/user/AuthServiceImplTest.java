package com.leadmanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.common.security.JwtProperties;
import com.leadmanager.api.common.security.JwtService;

/**
 * Pure unit tests for {@link AuthServiceImpl}. No Spring, no Postgres, no
 * Docker. Mockito stubs out {@link UserRepository}, {@link PasswordEncoder},
 * and {@link JwtService}; the test cares about the orchestration AuthService
 * does between them, not their internals (those have their own tests).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private final JwtProperties props = new JwtProperties(
            "test-secret-of-32-bytes-or-more!!!!",
            "lead-manager-test",
            Duration.ofMinutes(15));

    @InjectMocks
    private AuthServiceImpl authService;

    AuthServiceImplTest() {
    }

    /** {@link InjectMocks} won't see {@code props} (it's not a {@link Mock}), so we wire it manually. */
    @org.junit.jupiter.api.BeforeEach
    void wireProperties() {
        authService = new AuthServiceImpl(userRepository, passwordEncoder, jwtService, props);
    }

    private User buildUser(long id, String email, String hash) {
        User u = User.builder()
                .email(email)
                .passwordHash(hash)
                .displayName("Alice")
                .phone(null)
                .build();
        try {
            java.lang.reflect.Field f = User.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return u;
    }

    @Test
    void login_happyPath_returnsTokenWithCorrectTtlAndUserId() {
        User user = buildUser(42L, "alice@example.com", "$2a$12$storedHash");
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctpassword", "$2a$12$storedHash")).thenReturn(true);
        when(jwtService.issueAccessToken(42L)).thenReturn("issued.jwt.token");

        LoginResult result = authService.login(new LoginCommand("alice@example.com", "correctpassword"));

        assertThat(result.accessToken()).isEqualTo("issued.jwt.token");
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.expiresInSeconds()).isEqualTo(15L * 60);
    }

    @Test
    void login_stripsEmailAndLooksUpCaseInsensitively() {
        User user = buildUser(1L, "edan@example.com", "$2a$12$hash");
        when(userRepository.findByEmailIgnoreCase("edan@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), eq("$2a$12$hash"))).thenReturn(true);
        when(jwtService.issueAccessToken(1L)).thenReturn("t");

        authService.login(new LoginCommand("  edan@example.com  ", "pw"));

        verify(userRepository).findByEmailIgnoreCase("edan@example.com");
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        User user = buildUser(1L, "alice@example.com", "$2a$12$storedHash");
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$12$storedHash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginCommand("alice@example.com", "wrong")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(jwtService, never()).issueAccessToken(any());
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials_butStillCallsBcryptForTimingParity() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());
        // The service computes bcrypt against a dummy hash whether the user exists or not,
        // so an attacker cannot use response time to discover which emails are registered.
        when(passwordEncoder.matches(eq("any-password"), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginCommand("nobody@example.com", "any-password")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        // Crucially: the encoder was still invoked even though the user is missing.
        verify(passwordEncoder).matches(eq("any-password"), any());
        verify(jwtService, never()).issueAccessToken(any());
    }
}

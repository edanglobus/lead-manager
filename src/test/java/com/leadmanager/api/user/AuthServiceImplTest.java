package com.leadmanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @Mock
    private RefreshTokenService refreshTokenService;

    private final JwtProperties props = new JwtProperties(
            "test-secret-of-32-bytes-or-more!!!!",
            "lead-manager-test",
            Duration.ofMinutes(15),
            Duration.ofDays(30));

    private AuthServiceImpl authService;

    AuthServiceImplTest() {
    }

    /** Wire manually — {@code @InjectMocks} cannot see the non-mocked {@code props}. */
    @org.junit.jupiter.api.BeforeEach
    void wireProperties() {
        authService = new AuthServiceImpl(
                userRepository, passwordEncoder, jwtService, props, refreshTokenService);
    }

    private RefreshToken stubRefreshTokenEntity(long userId) {
        RefreshToken rt = RefreshToken.builder()
                .userId(userId)
                .tokenHash("a".repeat(64))
                .expiresAt(Instant.parse("2026-06-23T10:00:00Z"))
                .build();
        try {
            java.lang.reflect.Field f = RefreshToken.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(rt, 1L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return rt;
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
    void login_happyPath_returnsTokenWithCorrectTtlAndUserId_andIssuesRefresh() {
        User user = buildUser(42L, "alice@example.com", "$2a$12$storedHash");
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctpassword", "$2a$12$storedHash")).thenReturn(true);
        when(jwtService.issueAccessToken(42L)).thenReturn("issued.jwt.token");
        when(refreshTokenService.issue(42L))
                .thenReturn(new RefreshTokenService.Issued("refresh-plaintext", stubRefreshTokenEntity(42L)));

        LoginResult result = authService.login(new LoginCommand("alice@example.com", "correctpassword"));

        assertThat(result.accessToken()).isEqualTo("issued.jwt.token");
        assertThat(result.refreshToken()).isEqualTo("refresh-plaintext");
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.expiresInSeconds()).isEqualTo(15L * 60);
    }

    @Test
    void login_stripsEmailAndLooksUpCaseInsensitively() {
        User user = buildUser(1L, "edan@example.com", "$2a$12$hash");
        when(userRepository.findByEmailIgnoreCase("edan@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), eq("$2a$12$hash"))).thenReturn(true);
        when(jwtService.issueAccessToken(1L)).thenReturn("t");
        when(refreshTokenService.issue(1L))
                .thenReturn(new RefreshTokenService.Issued("r", stubRefreshTokenEntity(1L)));

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
        verify(refreshTokenService, never()).issue(any());
    }

    // ---------- refresh() tests ----------

    @Test
    void refresh_happyPath_revokesOldAndIssuesFreshPair() {
        RefreshToken existing = stubRefreshTokenEntity(7L);
        RefreshToken newEntity = stubRefreshTokenEntity(7L);

        when(refreshTokenService.findActive("old-plaintext")).thenReturn(Optional.of(existing));
        when(jwtService.issueAccessToken(7L)).thenReturn("new.access.token");
        when(refreshTokenService.issue(7L))
                .thenReturn(new RefreshTokenService.Issued("new-plaintext", newEntity));

        LoginResult result = authService.refresh("old-plaintext");

        assertThat(result.accessToken()).isEqualTo("new.access.token");
        assertThat(result.refreshToken()).isEqualTo("new-plaintext");
        assertThat(result.userId()).isEqualTo(7L);

        // The chain link must have been written.
        verify(refreshTokenService).replace(existing, newEntity);
    }

    @Test
    void refresh_invalidPlaintext_throwsInvalidRefreshToken() {
        when(refreshTokenService.findActive("garbage")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("garbage"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));

        // Nothing was issued, no rotation linked.
        verify(jwtService, never()).issueAccessToken(any());
        verify(refreshTokenService, never()).issue(any());
        verify(refreshTokenService, never()).replace(any(), any());
    }
}

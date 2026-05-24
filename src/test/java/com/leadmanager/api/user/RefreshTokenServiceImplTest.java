package com.leadmanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.leadmanager.api.common.security.JwtProperties;

/**
 * Pure unit tests for {@link RefreshTokenServiceImpl}. {@link RefreshTokenRepository}
 * is mocked; the {@link SecureRandom} is a deterministic seed so the
 * generated plaintext is reproducible across test runs.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceImplTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private final JwtProperties props = new JwtProperties(
            "test-secret-of-32-bytes-or-more!!!!",
            "lead-manager-test",
            Duration.ofMinutes(15),
            Duration.ofDays(30));

    private static final Instant T0 = Instant.parse("2026-05-24T12:00:00Z");

    private RefreshTokenServiceImpl service(Clock clock) {
        // Seed the SecureRandom so test output is deterministic.
        return new RefreshTokenServiceImpl(
                refreshTokenRepository,
                props,
                new SecureRandom(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}),
                clock);
    }

    @Test
    void issue_persistsHashOnly_andReturnsPlaintext() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenService.Issued result = service(clock).issue(42L);

        assertThat(result.plaintext()).isNotBlank();
        // base64url of 32 random bytes is 43 chars (no padding).
        assertThat(result.plaintext()).hasSize(43);

        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(savedCaptor.capture());
        RefreshToken saved = savedCaptor.getValue();

        assertThat(saved.getUserId()).isEqualTo(42L);
        // The persisted hash MUST NOT be the plaintext (hash is sha-256 hex = 64 chars).
        assertThat(saved.getTokenHash()).hasSize(64).isNotEqualTo(result.plaintext());
        assertThat(saved.getExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(saved.getRevokedAt()).isNull();
    }

    @Test
    void findActive_returnsToken_whenHashMatchesAndStillValid() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        RefreshTokenServiceImpl svc = service(clock);

        // Issue a token so we can then look it up.
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        RefreshTokenService.Issued issued = svc.issue(1L);

        when(refreshTokenRepository.findByTokenHash(issued.entity().getTokenHash()))
                .thenReturn(Optional.of(issued.entity()));

        Optional<RefreshToken> found = svc.findActive(issued.plaintext());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(1L);
    }

    @Test
    void findActive_returnsEmpty_whenTokenUnknown() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThat(service(clock).findActive("totally-bogus")).isEmpty();
    }

    @Test
    void findActive_returnsEmpty_whenTokenExpired() {
        Clock issuedAtClock = Clock.fixed(T0, ZoneOffset.UTC);
        RefreshTokenServiceImpl issuingSvc = service(issuedAtClock);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        RefreshTokenService.Issued issued = issuingSvc.issue(1L);

        // 31 days later — past the 30-day TTL.
        Clock laterClock = Clock.fixed(T0.plus(Duration.ofDays(31)), ZoneOffset.UTC);
        RefreshTokenServiceImpl laterSvc = service(laterClock);
        when(refreshTokenRepository.findByTokenHash(issued.entity().getTokenHash()))
                .thenReturn(Optional.of(issued.entity()));

        assertThat(laterSvc.findActive(issued.plaintext())).isEmpty();
    }

    @Test
    void findActive_returnsEmpty_whenTokenRevoked() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        RefreshTokenServiceImpl svc = service(clock);

        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        RefreshTokenService.Issued issued = svc.issue(1L);
        issued.entity().revoke(T0);

        when(refreshTokenRepository.findByTokenHash(issued.entity().getTokenHash()))
                .thenReturn(Optional.of(issued.entity()));

        assertThat(svc.findActive(issued.plaintext())).isEmpty();
    }

    @Test
    void findActive_returnsEmpty_whenInputIsNullOrBlank() {
        RefreshTokenServiceImpl svc = service(Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(svc.findActive(null)).isEmpty();
        assertThat(svc.findActive("")).isEmpty();
        assertThat(svc.findActive("   ")).isEmpty();
    }

    @Test
    void replace_marksOldRevokedAndLinksToNew() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenServiceImpl svc = service(clock);
        RefreshTokenService.Issued first = svc.issue(7L);
        RefreshTokenService.Issued second = svc.issue(7L);

        // Pretend the second was assigned id=99 by JPA.
        setField(second.entity(), "id", 99L);

        svc.replace(first.entity(), second.entity());

        assertThat(first.entity().getRevokedAt()).isEqualTo(T0);
        assertThat(first.entity().getReplacedById()).isEqualTo(99L);
        // The new token is not modified by replace().
        assertThat(second.entity().getRevokedAt()).isNull();
    }

    @Test
    void revokeAllActiveForUser_delegatesToRepoWithCurrentClock() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        when(refreshTokenRepository.revokeAllActiveForUser(eq(42L), eq(T0))).thenReturn(3);

        int n = service(clock).revokeAllActiveForUser(42L);

        assertThat(n).isEqualTo(3);
        verify(refreshTokenRepository).revokeAllActiveForUser(42L, T0);
    }

    // ---------- helpers ----------

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
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

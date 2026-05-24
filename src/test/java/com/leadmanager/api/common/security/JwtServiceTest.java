package com.leadmanager.api.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.leadmanager.api.common.security.JwtService.InvalidTokenException;

/**
 * Pure unit tests for {@link JwtService} — no Spring, no Postgres, no Docker.
 * <p>
 * Time is driven by an adjustable {@link Clock} so token-expiry behaviour is
 * deterministic and tests don't have to sleep.
 */
class JwtServiceTest {

    // 32 bytes — the minimum HS256 accepts.
    private static final JwtProperties PROPS = new JwtProperties(
            "test-secret-of-32-bytes-or-more!!!!",
            "lead-manager-test",
            Duration.ofMinutes(15),
            Duration.ofDays(30)
    );

    private static final Instant T0 = Instant.parse("2026-05-24T10:00:00Z");

    @Test
    void issuedToken_roundTripsBackToSameSubject() {
        JwtService svc = new JwtService(PROPS, Clock.fixed(T0, ZoneOffset.UTC));

        String token = svc.issueAccessToken(42L);
        long subject = svc.parseAccessToken(token);

        assertThat(subject).isEqualTo(42L);
        // Compact JWTs have exactly three base64url segments separated by dots.
        assertThat(token.chars().filter(c -> c == '.').count()).isEqualTo(2);
    }

    @Test
    void parseAccessToken_rejectsExpiredToken() {
        Adjustable clock = new Adjustable(T0);
        JwtService svc = new JwtService(PROPS, clock);

        String token = svc.issueAccessToken(7L);

        // Walk past the 15-minute TTL by one second.
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThatThrownBy(() -> svc.parseAccessToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void parseAccessToken_rejectsTokenSignedWithDifferentSecret() {
        JwtService issuer = new JwtService(PROPS, Clock.fixed(T0, ZoneOffset.UTC));
        JwtService verifier = new JwtService(
                new JwtProperties(
                        "a-different-secret-also-32-bytes!!",
                        PROPS.issuer(),
                        PROPS.accessTokenTtl(),
                        PROPS.refreshTokenTtl()),
                Clock.fixed(T0, ZoneOffset.UTC));

        String token = issuer.issueAccessToken(1L);

        assertThatThrownBy(() -> verifier.parseAccessToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void parseAccessToken_rejectsTokenWithWrongIssuer() {
        JwtService foreign = new JwtService(
                new JwtProperties(PROPS.secret(), "some-other-issuer", PROPS.accessTokenTtl(), PROPS.refreshTokenTtl()),
                Clock.fixed(T0, ZoneOffset.UTC));
        JwtService ours = new JwtService(PROPS, Clock.fixed(T0, ZoneOffset.UTC));

        String token = foreign.issueAccessToken(1L);

        assertThatThrownBy(() -> ours.parseAccessToken(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void parseAccessToken_rejectsTamperedSignature() {
        JwtService svc = new JwtService(PROPS, Clock.fixed(T0, ZoneOffset.UTC));
        String token = svc.issueAccessToken(99L);

        // Flip the last char of the signature segment.
        char last = token.charAt(token.length() - 1);
        char swapped = last == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + swapped;

        assertThatThrownBy(() -> svc.parseAccessToken(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void parseAccessToken_rejectsGarbageInput() {
        JwtService svc = new JwtService(PROPS, Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> svc.parseAccessToken("not-a-jwt"))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> svc.parseAccessToken(""))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> svc.parseAccessToken(null))
                .isInstanceOf(InvalidTokenException.class);
    }

    /** Tiny clock helper — saves writing the same anonymous-class boilerplate four times. */
    private static final class Adjustable extends Clock {
        private Instant now;

        Adjustable(Instant start) {
            this.now = start;
        }

        void advance(Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

package com.leadmanager.api.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies access JWTs.
 * <p>
 * <b>Token shape.</b> Signed with HMAC-SHA256 (HS256). Claim layout:
 * <ul>
 *   <li>{@code sub} (subject) — the {@code User.id} as a string.</li>
 *   <li>{@code iss} (issuer) — from {@link JwtProperties#issuer()}.</li>
 *   <li>{@code iat} (issued-at) — server clock.</li>
 *   <li>{@code exp} (expires) — issued-at + {@link JwtProperties#accessTokenTtl()}.</li>
 * </ul>
 * <p>
 * <b>Why HS256 and not RS256.</b> A symmetric secret is fine while there
 * is exactly one issuer and one verifier (this service). When we expose
 * the API to third-party consumers we'll rotate to RS256 so they can verify
 * with a public key without holding the signing secret; that swap touches
 * only this class.
 * <p>
 * <b>Why this class accepts a {@link Clock}.</b> Token expiration is the
 * trickiest thing to unit-test: with a real wall clock you'd have to
 * {@code Thread.sleep}. Constructor-injecting a {@code Clock} lets tests
 * pass a fixed clock and advance time without sleeping.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final JwtProperties properties;
    private final Clock clock;

    /**
     * Production constructor used by Spring. Defaults to a UTC system clock;
     * deterministic time is only needed in tests, where the
     * {@linkplain #JwtService(JwtProperties, Clock) two-arg constructor}
     * lets a {@link Clock#fixed} be supplied.
     * <p>
     * The {@link Autowired} annotation is REQUIRED here: with two
     * constructors of different visibilities and no annotation, Spring
     * falls back to looking for a no-arg constructor and fails. Marking
     * this one tells the container which to pick.
     */
    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Visible for testing — lets a fixed/adjustable {@link Clock} drive time. */
    JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // hmacShaKeyFor enforces the >=256-bit length internally; the
        // JwtProperties @Size(min=32) is the friendlier first-line check.
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Issue a fresh access token for the given user id.
     *
     * @return a compact, URL-safe JWT (3 dot-separated base64url segments)
     */
    public String issueAccessToken(Long userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verify a token's signature, issuer, and expiry and return its subject
     * as a numeric user id.
     *
     * @throws InvalidTokenException if the token is missing, tampered, expired,
     *                               issued by someone else, or has a non-numeric
     *                               subject. The wrapping is deliberate: callers
     *                               should not have to know about every JJWT
     *                               exception subtype.
     */
    public long parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException("token expired", e);
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException covers: bad signature, malformed token, wrong issuer.
            // IllegalArgumentException covers both JJWT's null/empty-input check
            // AND a tampered-but-validly-signed non-numeric subject (the
            // Long.parseLong call above throws NumberFormatException, which
            // extends IllegalArgumentException).
            throw new InvalidTokenException("invalid token", e);
        }
    }

    /**
     * Marker exception thrown from {@link #parseAccessToken(String)}. The
     * security filter (sub-step 1.4b) catches this and translates it into
     * a {@code 401 Unauthorized} RFC 7807 response.
     */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

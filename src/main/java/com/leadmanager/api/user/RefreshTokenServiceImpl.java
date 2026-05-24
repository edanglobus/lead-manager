package com.leadmanager.api.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.leadmanager.api.common.security.JwtProperties;

/**
 * Default {@link RefreshTokenService}.
 * <p>
 * <b>Token shape.</b> 256 bits of cryptographically-strong randomness,
 * base64url-encoded (URL- and header-safe, no padding). Stored as a
 * lowercase hex SHA-256 digest in {@code refresh_tokens.token_hash}.
 * The plaintext leaves the server exactly once — in the HTTP response
 * for {@code /auth/login} or {@code /auth/refresh}.
 * <p>
 * <b>Why SHA-256 (not bcrypt) for the hash.</b> Refresh tokens are
 * high-entropy server-generated secrets, not low-entropy human passwords.
 * They don't need the brute-force resistance bcrypt provides; what they
 * need is a fast, deterministic, constant-time lookup, which SHA-256 over
 * a unique index gives us.
 */
@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenServiceImpl.class);

    /** 256 bits — same as the JWT signing key floor. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Autowired
    public RefreshTokenServiceImpl(RefreshTokenRepository refreshTokenRepository,
                                   JwtProperties jwtProperties) {
        this(refreshTokenRepository, jwtProperties, new SecureRandom(), Clock.systemUTC());
    }

    /** Visible for testing — lets a deterministic random + clock be supplied. */
    RefreshTokenServiceImpl(RefreshTokenRepository refreshTokenRepository,
                            JwtProperties jwtProperties,
                            SecureRandom secureRandom,
                            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Issued issue(Long userId) {
        String plaintext = generatePlaintext();
        String hash = sha256Hex(plaintext);
        Instant expiresAt = clock.instant().plus(jwtProperties.refreshTokenTtl());

        RefreshToken entity = RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash)
                .expiresAt(expiresAt)
                .build();
        RefreshToken saved = refreshTokenRepository.save(entity);

        log.debug("Issued refresh token id={} for user={}", saved.getId(), userId);
        return new Issued(plaintext, saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findActive(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(plaintext);
        Instant now = clock.instant();
        return refreshTokenRepository.findByTokenHash(hash)
                .filter(t -> t.isActive(now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void replace(RefreshToken oldToken, RefreshToken newToken) {
        oldToken.revoke(clock.instant());
        oldToken.setReplacedById(newToken.getId());
        // No explicit save() — oldToken is a managed entity; Hibernate
        // flushes the UPDATE at transaction commit.
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public int revokeAllActiveForUser(Long userId) {
        int n = refreshTokenRepository.revokeAllActiveForUser(userId, clock.instant());
        log.info("Revoked {} refresh token(s) for user {}", n, userId);
        return n;
    }

    // ---------- crypto helpers ----------

    private String generatePlaintext() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory for every JRE per the JCA spec.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

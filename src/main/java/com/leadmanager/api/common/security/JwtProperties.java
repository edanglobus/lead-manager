package com.leadmanager.api.common.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Strongly-typed view of the {@code lead-manager.jwt.*} block in
 * {@code application.yml}.
 * <p>
 * Why a {@code @ConfigurationProperties record} (vs scattering
 * {@code @Value("${...}")} reads across services):
 * <ul>
 *   <li><b>Validation at startup.</b> The {@code @Validated} class-level
 *       annotation + {@link NotBlank} / {@link Size} / {@link NotNull} on
 *       components mean a missing or too-short {@code JWT_SECRET} fails
 *       app boot with a clear message, not on the first signing attempt.</li>
 *   <li><b>SoC.</b> Every JWT-aware class depends on this one type instead
 *       of magic strings.</li>
 *   <li><b>Records are immutable.</b> The settings cannot be mutated at
 *       runtime by accident.</li>
 * </ul>
 *
 * @param secret          HMAC-SHA256 secret. MUST be at least 32 bytes
 *                        (256 bits) per RFC 7518 §3.2.
 * @param issuer          {@code iss} claim written into every issued token
 *                        and required on every verified token.
 * @param accessTokenTtl  How long an access token is valid after issuance.
 */
@Validated
@ConfigurationProperties(prefix = "lead-manager.jwt")
public record JwtProperties(

        @NotBlank(message = "lead-manager.jwt.secret is required")
        @Size(min = 32, message = "lead-manager.jwt.secret must be at least 32 bytes for HS256")
        String secret,

        @NotBlank(message = "lead-manager.jwt.issuer is required")
        String issuer,

        @NotNull(message = "lead-manager.jwt.access-token-ttl is required")
        Duration accessTokenTtl
) {
}

package com.leadmanager.api.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides the system-wide {@link PasswordEncoder} bean.
 * <p>
 * Why this lives in its own {@code @Configuration} (and NOT inside the future
 * {@code SecurityConfig}):
 * <ul>
 *   <li><b>SoC.</b> Password hashing is a pure crypto primitive used by both
 *       registration and login. The filter chain is a different concern;
 *       conflating them in one class makes both harder to reason about.</li>
 *   <li><b>Slice ordering.</b> Sub-step 1.2 needs {@code PasswordEncoder} but
 *       does NOT need the security filter chain (which arrives in 1.4 with
 *       JWT). Keeping them in separate configs lets each slice introduce
 *       exactly what it needs without disturbing the other.</li>
 *   <li><b>Tests.</b> A unit test that exercises hashing can import this
 *       config alone via {@code @ContextConfiguration} without dragging in
 *       the whole security filter chain.</li>
 * </ul>
 * <p>
 * Bcrypt strength is set to {@code 12}. This is the OWASP-recommended floor
 * as of 2026; raising it later is non-breaking because every hash carries
 * its own work-factor prefix.
 */
@Configuration
public class PasswordEncoderConfig {

    private static final int BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}

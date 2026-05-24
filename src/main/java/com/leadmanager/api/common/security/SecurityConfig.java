package com.leadmanager.api.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.leadmanager.api.common.api.ApiVersion;

/**
 * Defines the Spring Security policy for the API.
 * <p>
 * Stateless: no HTTP session is ever created or read. Every request must
 * carry its own bearer token, and {@link JwtAuthFilter} promotes that token
 * to a populated {@code SecurityContext} for the duration of the request.
 * <p>
 * <b>Public paths.</b> Limited and explicit:
 * <ul>
 *   <li>{@code /api/v1/auth/**} — anyone can register or log in.</li>
 *   <li>{@code /actuator/health} and {@code /actuator/info} — required by
 *       monitoring and Kubernetes probes.</li>
 *   <li>{@code GET /error} — Spring's internal forward path for unhandled errors.</li>
 * </ul>
 * Everything else requires a valid JWT.
 * <p>
 * <b>Why CSRF is disabled.</b> CSRF protection is meaningful for browser
 * sessions where cookies are auto-sent; a stateless bearer-token API has
 * no ambient credentials to forge. Disabling CSRF here matches industry
 * practice for JWT APIs and is documented so reviewers don't flag it.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          JwtAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless API: no CSRF, no HTTP session.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Path-level policy. Everything not listed defaults to "authenticated".
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers(ApiVersion.V1 + "/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/error").permitAll()
                        .anyRequest().authenticated()
                )

                // Our JWT filter runs BEFORE UsernamePasswordAuthenticationFilter.
                // The latter is the default form-login filter Spring would otherwise
                // place in the chain; ordering our filter ahead of it ensures the
                // SecurityContext is populated by the time Spring's authorization
                // layer evaluates the rule for the path.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // RFC 7807 401 body instead of Spring's default empty 401.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))

                // No browser-friendly login forms or HTTP Basic challenge — this is a JSON API.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }
}

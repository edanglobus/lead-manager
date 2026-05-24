package com.leadmanager.api.common.security;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.leadmanager.api.common.security.JwtService.InvalidTokenException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads a {@code Bearer <token>} from the {@code Authorization} header on
 * every request, verifies the token via {@link JwtService}, and, on success,
 * places an {@link UsernamePasswordAuthenticationToken} into the
 * {@link SecurityContextHolder} so downstream controllers can read the
 * caller's identity via {@code @AuthenticationPrincipal AuthenticatedUser}.
 * <p>
 * <b>Why {@link OncePerRequestFilter}.</b> The chain may invoke a filter
 * multiple times on async dispatch / error dispatch; this base ensures
 * {@code doFilterInternal} runs at most once per HTTP request.
 * <p>
 * <b>What this filter does NOT do.</b> It does not reject requests on
 * its own. A missing or invalid token simply leaves the
 * {@code SecurityContext} empty, and Spring Security's
 * {@code AuthorizationFilter} decides whether the configured rule for the
 * path requires authentication. This separation keeps the filter
 * dumb (just translate token → principal) and the policy (which paths
 * need auth) in one place — {@code SecurityConfig}.
 * <p>
 * <b>Role.</b> Every authenticated request gets a single
 * {@code ROLE_USER} authority. Role granularity (admin, customer support,
 * etc.) is a later slice; for now there is one kind of caller.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                long userId = jwtService.parseAccessToken(token);
                authenticate(userId, request);
            } catch (InvalidTokenException e) {
                // Log at DEBUG so failed parses don't pollute production logs
                // (they're routine for expired tokens). The downstream
                // AuthorizationFilter will return 401 via JwtAuthenticationEntryPoint
                // when the request actually needs auth.
                log.debug("Rejected token: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }

    private void authenticate(long userId, HttpServletRequest request) {
        AuthenticatedUser principal = new AuthenticatedUser(userId);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null, // credentials — null after authentication has already happened
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

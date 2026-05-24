package com.leadmanager.api.common.security;

/**
 * The "principal" that {@link JwtAuthFilter} puts into Spring's
 * {@code SecurityContextHolder} after a valid bearer token is verified.
 * <p>
 * Controllers read this via {@code @AuthenticationPrincipal AuthenticatedUser me}
 * — see {@code UserController.getMe} for the canonical usage.
 * <p>
 * Kept as a record (immutable, no behaviour) on purpose: the principal is
 * extracted strictly from the JWT and should not carry mutable state or
 * fetched-on-demand fields. If a downstream caller needs more than the
 * id, they fetch a {@code User} from the repository themselves.
 */
public record AuthenticatedUser(Long id) {
}

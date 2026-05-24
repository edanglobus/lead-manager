package com.leadmanager.api.user.web;

/**
 * HTTP response body for {@code POST /api/v1/auth/login}.
 * <p>
 * Field naming matches the OAuth2 token-response convention
 * ({@code access_token}, {@code token_type}, {@code expires_in}) so
 * off-the-shelf mobile and web clients can consume it without
 * customization. Jackson is configured project-wide to leave field
 * names as-typed, so we spell the Java fields the same way here.
 *
 * @param accessToken the compact JWS to put into the
 *                    {@code Authorization: Bearer …} header on every
 *                    subsequent request
 * @param tokenType   always {@code "Bearer"} for now; reserved for future
 *                    schemes (DPoP, MTLS-bound, etc.)
 * @param expiresIn   wall-clock seconds until the access token expires;
 *                    clients should refresh slightly before this elapses
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken
) {
    public static LoginResponse bearer(String accessToken, long expiresInSeconds, String refreshToken) {
        return new LoginResponse(accessToken, "Bearer", expiresInSeconds, refreshToken);
    }
}

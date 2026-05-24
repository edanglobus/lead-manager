package com.leadmanager.api.user;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.common.security.JwtProperties;
import com.leadmanager.api.common.security.JwtService;

/**
 * Default {@link AuthService} backed by {@link UserRepository},
 * {@link PasswordEncoder}, {@link JwtService}, and {@link RefreshTokenService}.
 * <p>
 * Both {@link #login(LoginCommand)} and {@link #refresh(String)} are
 * write transactions because each call persists a new {@code refresh_tokens}
 * row (and refresh additionally revokes the previous one in the same
 * transaction, so a crash mid-rotation either commits both changes or
 * neither).
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           JwtProperties jwtProperties,
                           RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public LoginResult login(LoginCommand command) {
        Objects.requireNonNull(command, "command");

        String email = command.email() == null ? null : command.email().strip();

        // Look up the user. If the email isn't found we still call
        // passwordEncoder.matches with a dummy hash to keep the response
        // time roughly constant — otherwise the timing difference between
        // "unknown email" (fast) and "wrong password" (slow bcrypt) leaks
        // which emails are registered.
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        String hashToCheck = user != null ? user.getPasswordHash() : DUMMY_BCRYPT_HASH;

        if (!passwordEncoder.matches(command.rawPassword(), hashToCheck) || user == null) {
            // Generic detail — never reveal whether the email exists.
            log.info("Failed login attempt");
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        return issuePair(user.getId(), "logged in");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public LoginResult refresh(String refreshTokenPlaintext) {
        RefreshToken existing = refreshTokenService.findActive(refreshTokenPlaintext)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_REFRESH_TOKEN,
                        "Refresh token is invalid or expired"));

        Long userId = existing.getUserId();
        String accessToken = jwtService.issueAccessToken(userId);
        RefreshTokenService.Issued newRefresh = refreshTokenService.issue(userId);
        // Atomic within this transaction: old is revoked, new is persisted,
        // chain pointer set. A crash here would roll back all three.
        refreshTokenService.replace(existing, newRefresh.entity());

        log.info("User {} refreshed token", userId);
        return new LoginResult(
                accessToken,
                jwtProperties.accessTokenTtl().toSeconds(),
                newRefresh.plaintext(),
                userId);
    }

    /** Shared "issue a fresh (access, refresh) pair for this user" path. */
    private LoginResult issuePair(Long userId, String logVerb) {
        String accessToken = jwtService.issueAccessToken(userId);
        RefreshTokenService.Issued refresh = refreshTokenService.issue(userId);

        log.info("User {} {}", userId, logVerb);
        return new LoginResult(
                accessToken,
                jwtProperties.accessTokenTtl().toSeconds(),
                refresh.plaintext(),
                userId);
    }

    /**
     * A real bcrypt hash of an arbitrary string. We compare the caller's
     * password against this when the email lookup misses so the bcrypt
     * round runs in both branches and an attacker cannot distinguish
     * "user not found" from "wrong password" by response time.
     * <p>
     * The plaintext that produced this hash is irrelevant — no one ever
     * matches it intentionally.
     */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$abcdefghijklmnopqrstuOaTZ1jXynkxYlpyfwjHckpQ1k0EUyTm";
}

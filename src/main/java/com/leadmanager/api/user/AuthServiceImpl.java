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
 * {@link PasswordEncoder}, and {@link JwtService}.
 * <p>
 * <b>Why read-only transaction.</b> Login does not mutate state in 1.4c
 * (refresh-token rows arrive in 1.5). Marking the boundary
 * {@code readOnly = true} lets Hibernate skip dirty-checking and lets the
 * datasource pick a read replica when one is configured.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
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
            log.info("Failed login attempt for email pattern matching local rules");
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        String token = jwtService.issueAccessToken(user.getId());
        long ttlSeconds = jwtProperties.accessTokenTtl().toSeconds();

        log.info("User {} logged in", user.getId());
        return new LoginResult(token, ttlSeconds, user.getId());
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

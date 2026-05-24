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

/**
 * Default {@link UserService} backed by JPA and Spring Security's bcrypt
 * {@link PasswordEncoder}.
 * <p>
 * Concurrency note: the email-uniqueness check is a non-atomic
 * "exists-then-save" pair. The authoritative guard against duplicates is the
 * {@code users_email_lower_uq} unique index in {@code V3__create_users.sql};
 * two simultaneous registrations of the same email will see one transaction
 * succeed and the other fail with a {@code DataIntegrityViolationException}
 * at flush time. The cheap pre-check here gives the common case a clean
 * {@link ErrorCode#EMAIL_TAKEN} response; mapping the rarer race-condition
 * constraint violation to the same {@code ErrorCode} is the controller-layer
 * concern in sub-step 1.3 (via {@link com.leadmanager.api.common.exception.GlobalExceptionHandler}).
 */
@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public User register(RegisterUserCommand command) {
        Objects.requireNonNull(command, "command");

        String email = command.email() == null ? null : command.email().strip();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            // Generic detail: do not echo the email back (avoids enumeration helpers).
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "Email is already registered");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(command.rawPassword()))
                .displayName(command.displayName())
                .phone(command.phone())
                .build();

        User saved = userRepository.save(user);
        log.info("Registered user id={}", saved.getId());
        return saved;
    }
}

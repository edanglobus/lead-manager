package com.leadmanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;

/**
 * Pure unit tests for {@link UserServiceImpl}. The repository and the
 * {@link PasswordEncoder} are mocked so these tests run without a database,
 * Spring context, or Testcontainers. Integration coverage (real index
 * behaviour, actual bcrypt) is handled in the slice's MockMvc test in 1.3.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private static final String EMAIL = "Alice@Example.com";
    private static final String RAW_PASSWORD = "correct horse battery staple";
    private static final String HASHED = "$2a$12$abcdefghijklmnopqrstuv";

    @Test
    void register_persistsUserWithHashedPassword() {
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn(HASHED);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.register(
                new RegisterUserCommand(EMAIL, RAW_PASSWORD, "Alice", "+972-50-1234567"));

        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getPasswordHash()).isEqualTo(HASHED);
        assertThat(saved.getDisplayName()).isEqualTo("Alice");
        assertThat(saved.getPhone()).isEqualTo("+972-50-1234567");

        // The raw password must reach the encoder, NOT the repository.
        ArgumentCaptor<CharSequence> encoded = ArgumentCaptor.forClass(CharSequence.class);
        verify(passwordEncoder).encode(encoded.capture());
        assertThat(encoded.getValue()).isEqualTo(RAW_PASSWORD);
    }

    @Test
    void register_stripsLeadingAndTrailingWhitespaceFromEmail() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn(HASHED);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.register(
                new RegisterUserCommand("  alice@example.com  ", RAW_PASSWORD, "Alice", null));

        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        verify(userRepository).existsByEmailIgnoreCase("alice@example.com");
    }

    @Test
    void register_preservesCallerSuppliedEmailCase() {
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn(HASHED);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.register(
                new RegisterUserCommand(EMAIL, RAW_PASSWORD, "Alice", null));

        // Stored as typed; uniqueness lookups happen via LOWER(email) in the repo.
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void register_throwsEmailTaken_whenDuplicateExists() {
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> userService.register(
                new RegisterUserCommand(EMAIL, RAW_PASSWORD, "Alice", null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMAIL_TAKEN));

        // Crucially: we must NOT hash a password or hit save() for a known dupe.
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_duplicateCheckIsDelegatedToCaseInsensitiveQuery() {
        // The service must use existsByEmailIgnoreCase, not findById or save-and-pray.
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn(HASHED);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.register(new RegisterUserCommand(EMAIL, RAW_PASSWORD, "Alice", null));

        verify(userRepository).existsByEmailIgnoreCase(eq(EMAIL));
    }
}

package com.leadmanager.api.user;

/**
 * Application service for the {@code User} aggregate.
 * <p>
 * This is the single, authoritative entry point for any code that needs to
 * create or mutate users. Why an interface (and not a bare {@code @Service}
 * class):
 * <ul>
 *   <li>Lets test code wire a fake without spinning up Spring or Mockito for
 *       trivial collaborators.</li>
 *   <li>Future slices ({@code feat/profile-updates}, {@code feat/account-deletion})
 *       extend the contract here in one place; consumers depend on the port,
 *       not on whichever {@code Impl} happens to be active.</li>
 *   <li>Per {@code CLAUDE.md}, service interfaces are the documented home for
 *       business-rule JavaDoc — this is where the rationale for invariants
 *       (e.g. case-insensitive email uniqueness) is recorded.</li>
 * </ul>
 */
public interface UserService {

    /**
     * Registers a new provider.
     * <p>
     * Invariants enforced here (NOT at the controller):
     * <ul>
     *   <li>Email uniqueness is case-insensitive — backed by the
     *       {@code users_email_lower_uq} index. Attempting to register
     *       "Foo@bar.com" when "foo@bar.com" exists throws
     *       {@link com.leadmanager.api.common.exception.ApiException}
     *       with {@code ErrorCode.EMAIL_TAKEN}.</li>
     *   <li>The plaintext password is hashed via the configured
     *       {@code PasswordEncoder} before persistence and is never logged
     *       or returned.</li>
     *   <li>Email leading/trailing whitespace is stripped; the case the
     *       caller typed is preserved for display purposes (the unique
     *       index handles dedup).</li>
     * </ul>
     * Format validation (email shape, password length) is the controller's
     * responsibility and is NOT re-checked here.
     *
     * @param command the registration request; never {@code null}
     * @return the persisted {@link User}, including its generated {@code id}
     *         and audit timestamps
     */
    User register(RegisterUserCommand command);
}

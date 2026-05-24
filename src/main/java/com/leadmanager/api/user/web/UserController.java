package com.leadmanager.api.user.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leadmanager.api.common.api.ApiVersion;
import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.common.security.AuthenticatedUser;
import com.leadmanager.api.user.User;
import com.leadmanager.api.user.UserRepository;

/**
 * Authenticated user-facing endpoints. The single endpoint shipping in
 * sub-step 1.4b is {@code GET /api/v1/users/me} — its purpose is to give
 * the client an authenticated view of "who am I" without having to embed
 * profile fields in the JWT payload.
 * <p>
 * Per {@code CLAUDE.md}: thin controller, fat service. Reading the
 * current user from {@code @AuthenticationPrincipal} and mapping an
 * entity to a DTO is the controller's job; anything richer (e.g. profile
 * editing) belongs in {@code UserService}.
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/users")
public class UserController {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserController(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @GetMapping("/me")
    public UserResponse getMe(@AuthenticationPrincipal AuthenticatedUser me) {
        // SecurityConfig + JwtAuthFilter guarantee that a non-null principal
        // is present by the time we land here. If it's null we treat it as a
        // server-side bug and surface RESOURCE_NOT_FOUND so the wire contract
        // stays sane (a JWT with an id that no longer maps to a row is the
        // realistic case — e.g. the user was deleted after the token was minted).
        User user = userRepository.findById(me.id())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "User no longer exists"));
        return userMapper.toResponse(user);
    }
}

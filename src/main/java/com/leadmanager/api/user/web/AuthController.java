package com.leadmanager.api.user.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.leadmanager.api.common.api.ApiVersion;
import com.leadmanager.api.user.User;
import com.leadmanager.api.user.UserService;

import jakarta.validation.Valid;

/**
 * HTTP-layer entry point for authentication-related actions.
 * <p>
 * Sub-step 1.3 ships only {@code POST /register}. {@code /login},
 * {@code /refresh}, {@code /logout} arrive in 1.4 and 1.5.
 * <p>
 * Controller contract per {@code CLAUDE.md}: fat services, thin controllers.
 * The body of {@link #register} does four things and nothing else: parse,
 * convert, delegate, respond. Any business rule that lives here is a bug.
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/auth")
public class AuthController {

    private final UserService userService;
    private final UserMapper userMapper;

    public AuthController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    /**
     * Creates a new provider account.
     * <p>
     * Responses:
     * <ul>
     *   <li>{@code 201 Created} with a {@code Location} header pointing at
     *       the future {@code GET /users/{id}} resource and a
     *       {@link UserResponse} body on success.</li>
     *   <li>{@code 400 Bad Request} (RFC 7807) if Bean Validation fails.</li>
     *   <li>{@code 409 Conflict} (RFC 7807, {@code code=EMAIL_TAKEN}) if the
     *       email is already registered.</li>
     * </ul>
     */
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest request) {
        User saved = userService.register(userMapper.toCommand(request));
        UserResponse body = userMapper.toResponse(saved);

        return ResponseEntity
                .created(UriComponentsBuilder
                        .fromPath(ApiVersion.V1 + "/users/{id}")
                        .buildAndExpand(saved.getId())
                        .toUri())
                .body(body);
    }
}

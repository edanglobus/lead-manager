package com.leadmanager.api.common.security;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadmanager.api.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Translates a missing / failed authentication on a protected endpoint into
 * an RFC 7807 {@code application/problem+json} body so the wire contract
 * stays consistent with the rest of the API.
 * <p>
 * Spring Security's default behaviour on an unauthenticated request to a
 * protected route is to write an empty {@code 401 Unauthorized} response,
 * which would leave clients staring at a blank body and break the
 * "every error is RFC 7807" invariant. This component plugs in via
 * {@code SecurityConfig.exceptionHandling().authenticationEntryPoint(...)}.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        log.debug("401 on {} {} — {}", request.getMethod(), request.getRequestURI(), authException.getMessage());

        ErrorCode code = ErrorCode.UNAUTHENTICATED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), "Authentication is required to access this resource");
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

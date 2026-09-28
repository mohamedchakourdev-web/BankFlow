package com.bankflow.auth.security;

import com.bankflow.common.response.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String MISSING_TOKEN = "Authentication is required.";
    static final String INVALID_TOKEN = "Invalid or expired token.";

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        String message = JwtAuthenticationFilter.INVALID_TOKEN.equals(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR))
                ? INVALID_TOKEN
                : MISSING_TOKEN;
        write(response, HttpStatus.UNAUTHORIZED, message, request.getRequestURI());
    }

    public static void write(HttpServletResponse response, HttpStatus status, String message, String path, ObjectMapper objectMapper)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(status, message, path));
    }

    private void write(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        write(response, status, message, path, objectMapper);
    }
}

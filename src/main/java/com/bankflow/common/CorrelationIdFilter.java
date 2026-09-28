package com.bankflow.common;

import com.bankflow.auth.security.RestAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final ObjectMapper objectMapper;

    public CorrelationIdFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String correlationId;
        if (supplied == null || supplied.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else if (!VALID.matcher(supplied.trim()).matches()) {
            RestAuthenticationEntryPoint.write(
                    response,
                    HttpStatus.BAD_REQUEST,
                    "X-Correlation-Id must be 1 to 64 letters, digits, dots, underscores, or hyphens",
                    request.getRequestURI(),
                    objectMapper
            );
            return;
        } else {
            correlationId = supplied.trim();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

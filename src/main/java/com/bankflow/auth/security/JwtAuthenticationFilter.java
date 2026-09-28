package com.bankflow.auth.security;

import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR = "bankflow.auth.error";
    public static final String INVALID_TOKEN = "invalid";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() < BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            reject(request);
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JwtIdentity identity = jwtService.parse(token);
            userRepository.findById(identity.userId()).ifPresentOrElse(
                    this::authenticate,
                    () -> reject(request)
            );
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected bearer token ({})", ex.getClass().getSimpleName());
            reject(request);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getRole());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void reject(HttpServletRequest request) {
        request.setAttribute(AUTH_ERROR, INVALID_TOKEN);
        SecurityContextHolder.clearContext();
    }
}

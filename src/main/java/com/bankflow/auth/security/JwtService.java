package com.bankflow.auth.security;

import com.bankflow.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    static final int MINIMUM_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateToken(UUID userId, Role role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(properties.expirationSeconds());
        return issue(userId, role, issuedAt, expiresAt);
    }

    public String issue(UUID userId, Role role, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public JwtIdentity parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        UUID userId = readUserId(claims);
        Role role = readRole(claims);
        Instant issuedAt = claims.getIssuedAt().toInstant();
        Instant expiresAt = claims.getExpiration().toInstant();
        return new JwtIdentity(userId, role, issuedAt, expiresAt);
    }

    private UUID readUserId(Claims claims) {
        try {
            return UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new JwtException("Token subject is not a user id");
        }
    }

    private Role readRole(Claims claims) {
        try {
            return Role.valueOf(claims.get("role", String.class));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new JwtException("Token role is missing");
        }
    }
}

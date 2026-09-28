package com.bankflow.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID userId,
        String action,
        String entityType,
        UUID entityId,
        JsonNode metadata,
        Instant createdAt
) {

    public static AuditLogResponse from(AuditLog log, ObjectMapper objectMapper) {
        return new AuditLogResponse(
                log.getId(),
                log.getUser() == null ? null : log.getUser().getId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                metadata(log.getMetadata(), objectMapper),
                log.getCreatedAt()
        );
    }

    private static JsonNode metadata(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }
}

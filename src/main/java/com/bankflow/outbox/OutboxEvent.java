package com.bankflow.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 50)
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 100)
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    private int attempts;

    @Setter(AccessLevel.NONE)
    @Size(max = MAX_ERROR_LENGTH)
    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter(AccessLevel.NONE)
    @Column(name = "published_at")
    private Instant publishedAt;

    public static OutboxEvent pending(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload
    ) {
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.status = OutboxEventStatus.PENDING;
        event.attempts = 0;
        return event;
    }

    public void markPublished() {
        this.attempts = this.attempts + 1;
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void recordFailure(String error) {
        this.attempts = this.attempts + 1;
        this.lastError = truncate(error);
    }

    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return "Publication failed";
        }
        String trimmed = error.trim();
        return trimmed.length() <= MAX_ERROR_LENGTH ? trimmed : trimmed.substring(0, MAX_ERROR_LENGTH);
    }
}

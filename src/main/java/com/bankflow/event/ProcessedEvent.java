package com.bankflow.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    private UUID id;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 100)
    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public static ProcessedEvent start(UUID eventId, String consumerName) {
        ProcessedEvent event = new ProcessedEvent();
        event.id = UUID.randomUUID();
        event.eventId = eventId;
        event.consumerName = consumerName;
        return event;
    }
}

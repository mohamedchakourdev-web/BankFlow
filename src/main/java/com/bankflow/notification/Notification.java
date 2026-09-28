package com.bankflow.notification;

import com.bankflow.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    private UUID id;

    @Setter(AccessLevel.NONE)
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String title;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 500)
    @Column(nullable = false, length = 500)
    private String message;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 50)
    @Column(name = "related_entity_type", nullable = false, length = 50)
    private String relatedEntityType;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Column(name = "related_entity_id", nullable = false)
    private UUID relatedEntityId;

    @Setter(AccessLevel.NONE)
    @Column(name = "read", nullable = false)
    private boolean read;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Notification create(
            User user,
            NotificationType type,
            String title,
            String message,
            String relatedEntityType,
            UUID relatedEntityId
    ) {
        Notification notification = new Notification();
        notification.id = UUID.randomUUID();
        notification.user = user;
        notification.type = type;
        notification.title = title;
        notification.message = message;
        notification.relatedEntityType = relatedEntityType;
        notification.relatedEntityId = relatedEntityId;
        notification.read = false;
        return notification;
    }

    public void markRead() {
        this.read = true;
    }
}

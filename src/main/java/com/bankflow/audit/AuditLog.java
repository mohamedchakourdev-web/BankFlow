package com.bankflow.audit;

import com.bankflow.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String action;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 50)
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Setter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static AuditLog record(User user, String action, String entityType, UUID entityId, String metadata) {
        AuditLog log = new AuditLog();
        log.user = user;
        log.action = action;
        log.entityType = entityType;
        log.entityId = entityId;
        log.metadata = metadata;
        return log;
    }
}

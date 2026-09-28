package com.bankflow.idempotency;

import com.bankflow.transfer.Transfer;
import com.bankflow.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(
        name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "idempotency_keys_user_key_unique",
                columnNames = {"user_id", "idempotency_key"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 128)
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(min = 64, max = 64)
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id")
    private Transfer transfer;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static IdempotencyKey start(User user, String idempotencyKey, String requestFingerprint) {
        IdempotencyKey claim = new IdempotencyKey();
        claim.user = user;
        claim.idempotencyKey = idempotencyKey;
        claim.requestFingerprint = requestFingerprint;
        claim.status = IdempotencyStatus.IN_PROGRESS;
        return claim;
    }

    public void complete(Transfer transfer) {
        this.transfer = transfer;
        this.status = IdempotencyStatus.COMPLETED;
    }
}

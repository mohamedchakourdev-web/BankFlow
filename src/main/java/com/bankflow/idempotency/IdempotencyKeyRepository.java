package com.bankflow.idempotency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from IdempotencyKey k where k.user.id = :userId and k.idempotencyKey = :idempotencyKey")
    Optional<IdempotencyKey> findByUserIdAndKeyForUpdate(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    long countByUser_Id(UUID userId);

    long countByUser_IdAndIdempotencyKey(UUID userId, String idempotencyKey);
}

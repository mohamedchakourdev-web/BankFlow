package com.bankflow.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Override
    @EntityGraph(attributePaths = "user")
    Page<AuditLog> findAll(Pageable pageable);

    long countByActionAndEntityId(String action, UUID entityId);

    long countByAction(String action);
}

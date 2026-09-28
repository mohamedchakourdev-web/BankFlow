package com.bankflow.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByAggregateId(UUID aggregateId);

    @Query(value = """
            select *
            from outbox_events
            where status = 'PENDING'
            order by created_at, id
            limit :batch
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextPending(@Param("batch") int batch);
}

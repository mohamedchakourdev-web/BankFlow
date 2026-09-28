package com.bankflow.transfer.repository;

import com.bankflow.transfer.Transfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    long countBySourceAccount_Id(UUID sourceAccountId);

    @Query(
            value = """
                    select t from Transfer t
                    join fetch t.sourceAccount
                    join fetch t.destinationAccount
                    where t.sourceAccount.user.id = :userId or t.destinationAccount.user.id = :userId
                    """,
            countQuery = """
                    select count(t) from Transfer t
                    where t.sourceAccount.user.id = :userId or t.destinationAccount.user.id = :userId
                    """
    )
    Page<Transfer> findInvolvingUser(@Param("userId") UUID userId, Pageable pageable);

    @Query(
            value = """
                    select t from Transfer t
                    join fetch t.sourceAccount
                    join fetch t.destinationAccount
                    where (t.sourceAccount.user.id = :userId or t.destinationAccount.user.id = :userId)
                      and (t.sourceAccount.id = :accountId or t.destinationAccount.id = :accountId)
                    """,
            countQuery = """
                    select count(t) from Transfer t
                    where (t.sourceAccount.user.id = :userId or t.destinationAccount.user.id = :userId)
                      and (t.sourceAccount.id = :accountId or t.destinationAccount.id = :accountId)
                    """
    )
    Page<Transfer> findInvolvingUserAndAccount(
            @Param("userId") UUID userId,
            @Param("accountId") UUID accountId,
            Pageable pageable
    );

    @Query("""
            select t from Transfer t
            where t.id = :id
              and (t.sourceAccount.user.id = :userId or t.destinationAccount.user.id = :userId)
            """)
    Optional<Transfer> findByIdInvolvingUser(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(
            value = """
                    select t from Transfer t
                    join fetch t.sourceAccount
                    join fetch t.destinationAccount
                    where t.sourceAccount.id = :accountId or t.destinationAccount.id = :accountId
                    """,
            countQuery = """
                    select count(t) from Transfer t
                    where t.sourceAccount.id = :accountId or t.destinationAccount.id = :accountId
                    """
    )
    Page<Transfer> findByAccount(@Param("accountId") UUID accountId, Pageable pageable);
}

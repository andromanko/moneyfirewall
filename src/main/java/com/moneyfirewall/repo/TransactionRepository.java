package com.moneyfirewall.repo;

import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    @Query("""
            select t from Transaction t
            where t.budget.id = :budgetId
              and t.occurredAt >= :from
              and t.occurredAt < :to
            order by t.occurredAt asc
            """)
    List<Transaction> findAllInRange(@Param("budgetId") UUID budgetId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select t from Transaction t
            where t.budget.id = :budgetId
              and t.transferGroup is null
              and t.direction in :dirs
              and t.occurredAt >= :from
              and t.occurredAt < :to
            """)
    List<Transaction> findCandidatesForTransferLinking(
            @Param("budgetId") UUID budgetId,
            @Param("dirs") List<TransactionDirection> dirs,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("select t from Transaction t where t.transferGroup.id = :groupId")
    List<Transaction> findAllByTransferGroupId(@Param("groupId") UUID groupId);

    @Modifying
    @Transactional
    @Query("delete from Transaction t where t.importSession.id = :importSessionId")
    int deleteAllByImportSessionId(@Param("importSessionId") UUID importSessionId);
}


package com.moneyfirewall.repo;

import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    boolean existsByBudgetIdAndExternalHash(UUID budgetId, String externalHash);

    @Query("""
            select t from Transaction t
            left join fetch t.category c
            left join fetch c.parentCategory
            where t.budget.id = :budgetId
              and t.occurredAt >= :from
              and t.occurredAt < :to
            order by t.occurredAt asc
            """)
    List<Transaction> findAllInRange(@Param("budgetId") UUID budgetId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select t from Transaction t
            where t.budget.id = :budgetId
              and t.direction = com.moneyfirewall.domain.TransactionDirection.EXPENSE
              and t.transferGroup is null
              and t.occurredAt >= :from
              and t.occurredAt < :to
            """)
    List<Transaction> findExpensesInRange(@Param("budgetId") UUID budgetId, @Param("from") Instant from, @Param("to") Instant to);

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

    @Query("""
            select t.category.id, count(t) from Transaction t
            where t.budget.id = :budgetId
              and t.direction = com.moneyfirewall.domain.TransactionDirection.EXPENSE
              and t.category.id is not null
              and t.transferGroup is null
            group by t.category.id
            """)
    List<Object[]> countExpensesByCategoryId(@Param("budgetId") UUID budgetId);

    @Query("""
            select t.category.id, count(t) from Transaction t
            where t.budget.id = :budgetId
              and t.direction = com.moneyfirewall.domain.TransactionDirection.INCOME
              and t.category.id is not null
              and t.transferGroup is null
            group by t.category.id
            """)
    List<Object[]> countIncomesByCategoryId(@Param("budgetId") UUID budgetId);

    @Query("""
            select count(t) > 0 from Transaction t
            where t.budget.id = :budgetId
              and t.id <> :excludeId
              and t.direction = com.moneyfirewall.domain.TransactionDirection.INCOME
              and t.category.id = :categoryId
              and t.account.name = :accountName
              and t.amount = :amount
              and t.occurredAt >= :from
              and t.occurredAt < :to
            """)
    boolean existsOncePerMonthMatch(
            @Param("budgetId") UUID budgetId,
            @Param("excludeId") UUID excludeId,
            @Param("categoryId") UUID categoryId,
            @Param("accountName") String accountName,
            @Param("amount") BigDecimal amount,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}

package com.moneyfirewall.repo;

import com.moneyfirewall.domain.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    @Query("select a from Account a where a.budget.id = :budgetId order by a.createdAt asc")
    List<Account> findAllByBudgetId(@Param("budgetId") UUID budgetId);

    @Query("select a from Account a where a.budget.id = :budgetId and lower(a.name) = lower(:name)")
    Optional<Account> findByBudgetIdAndName(@Param("budgetId") UUID budgetId, @Param("name") String name);
}


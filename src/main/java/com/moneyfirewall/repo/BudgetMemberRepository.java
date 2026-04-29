package com.moneyfirewall.repo;

import com.moneyfirewall.domain.BudgetMember;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.BudgetRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetMemberRepository extends JpaRepository<BudgetMember, UUID> {
    @Query("select bm from BudgetMember bm where bm.budget.id = :budgetId and bm.user.id = :userId")
    Optional<BudgetMember> findMembership(@Param("budgetId") UUID budgetId, @Param("userId") UUID userId);

    @Query("select bm from BudgetMember bm join fetch bm.user where bm.budget.id = :budgetId")
    List<BudgetMember> findAllByBudgetId(@Param("budgetId") UUID budgetId);

    @Query("select (count(bm) > 0) from BudgetMember bm where bm.budget.id = :budgetId and bm.user.id = :userId and bm.role = :role")
    boolean hasRole(@Param("budgetId") UUID budgetId, @Param("userId") UUID userId, @Param("role") BudgetRole role);

    @Query("select distinct bm.budget from BudgetMember bm where bm.user.id = :userId")
    List<Budget> findBudgetsByUserId(@Param("userId") UUID userId);
}


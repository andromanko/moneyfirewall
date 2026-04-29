package com.moneyfirewall.repo;

import com.moneyfirewall.domain.MerchantAlias;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, UUID> {
    @Query("select a from MerchantAlias a where a.budget.id = :budgetId order by a.priority asc, a.createdAt asc")
    List<MerchantAlias> findAllByBudgetId(@Param("budgetId") UUID budgetId);
}


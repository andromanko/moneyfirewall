package com.moneyfirewall.repo;

import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    @Query("select c from Category c where c.budget.id = :budgetId and c.kind = :kind order by c.name asc")
    List<Category> findAllByBudgetIdAndKind(@Param("budgetId") UUID budgetId, @Param("kind") CategoryKind kind);

    @Query("select c from Category c where c.budget.id = :budgetId and c.kind = :kind and lower(c.name) = lower(:name)")
    Optional<Category> findByBudgetIdAndKindAndName(@Param("budgetId") UUID budgetId, @Param("kind") CategoryKind kind, @Param("name") String name);
}


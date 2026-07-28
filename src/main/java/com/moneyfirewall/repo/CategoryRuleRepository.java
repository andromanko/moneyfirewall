package com.moneyfirewall.repo;

import com.moneyfirewall.domain.CategoryRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {
    @Query("""
            select r from CategoryRule r
            join fetch r.category c
            left join fetch c.parentCategory
            where r.budget.id = :budgetId
            order by r.priority asc, r.createdAt asc
            """)
    List<CategoryRule> findAllByBudgetId(@Param("budgetId") UUID budgetId);

    @Query("""
            select r from CategoryRule r
            join fetch r.category c
            left join fetch c.parentCategory
            where r.id = :id
            """)
    Optional<CategoryRule> findByIdWithCategory(@Param("id") UUID id);

    @Query("""
            select r from CategoryRule r
            join fetch r.category c
            left join fetch c.parentCategory
            where r.budget.id = :budgetId and r.category.id = :categoryId
            order by r.priority asc, r.createdAt asc
            """)
    List<CategoryRule> findAllByBudgetIdAndCategoryId(@Param("budgetId") UUID budgetId, @Param("categoryId") UUID categoryId);
}


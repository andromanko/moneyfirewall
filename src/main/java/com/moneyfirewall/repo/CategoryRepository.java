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
    @Query("select c from Category c left join fetch c.parentCategory where c.budget.id = :budgetId and c.kind = :kind order by c.name asc")
    List<Category> findAllByBudgetIdAndKind(@Param("budgetId") UUID budgetId, @Param("kind") CategoryKind kind);

    @Query("select c from Category c where c.budget.id = :budgetId and c.kind = :kind and c.parentCategory is null order by c.name asc")
    List<Category> findAllParentsByBudgetIdAndKind(@Param("budgetId") UUID budgetId, @Param("kind") CategoryKind kind);

    @Query("select c from Category c where c.budget.id = :budgetId and c.kind = :kind and c.parentCategory.id = :parentId order by c.name asc")
    List<Category> findAllChildrenByBudgetIdAndKind(@Param("budgetId") UUID budgetId, @Param("kind") CategoryKind kind, @Param("parentId") UUID parentId);

    @Query("select c from Category c where c.budget.id = :budgetId and c.kind = :kind and c.parentCategory is null and lower(c.name) = lower(:name)")
    Optional<Category> findParentByBudgetIdAndKindAndName(@Param("budgetId") UUID budgetId, @Param("kind") CategoryKind kind, @Param("name") String name);

    @Query("select c from Category c where c.budget.id = :budgetId and c.kind = :kind and c.parentCategory.id = :parentId and lower(c.name) = lower(:name)")
    Optional<Category> findChildByBudgetIdAndKindAndParentIdAndName(@Param("budgetId") UUID budgetId, @Param("kind") CategoryKind kind, @Param("parentId") UUID parentId, @Param("name") String name);

    @Query("select c from Category c left join fetch c.parentCategory where c.id = :categoryId and c.budget.id = :budgetId")
    Optional<Category> findByIdAndBudgetId(@Param("categoryId") UUID categoryId, @Param("budgetId") UUID budgetId);
}


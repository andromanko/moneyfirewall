package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.CategoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;

    public CategoryService(CategoryRepository categoryRepository, BudgetRepository budgetRepository) {
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
    }

    @Transactional
    public Category ensure(UUID budgetId, CategoryKind kind, String name) {
        return categoryRepository.findByBudgetIdAndKindAndName(budgetId, kind, name)
                .orElseGet(() -> {
                    Budget budget = budgetRepository.findById(budgetId).orElseThrow();
                    Category c = new Category();
                    c.setBudget(budget);
                    c.setKind(kind);
                    c.setName(name);
                    c.setCreatedAt(Instant.now());
                    return categoryRepository.save(c);
                });
    }

    @Transactional(readOnly = true)
    public List<Category> list(UUID budgetId, CategoryKind kind) {
        return categoryRepository.findAllByBudgetIdAndKind(budgetId, kind);
    }
}


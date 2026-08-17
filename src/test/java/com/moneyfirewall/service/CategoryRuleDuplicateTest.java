package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.CategoryRule;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.CategoryRuleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Adding a rule via the interactive wizard/command path had no duplicate check at all (only the
 * JSON import path deduped, via {@code sameRule}); the same category + same pattern (regardless
 * of case) must now be rejected with a descriptive error instead of silently creating a redundant
 * rule.
 */
class CategoryRuleDuplicateTest {
    private static final UUID BUDGET_ID = UUID.randomUUID();

    @Test
    void sameCategoryAndPatternDifferingOnlyByCaseIsRejected() {
        CategoryRuleService service = newService();
        Category cat = category("Дети");
        stubCategory(cat);

        service.add(BUDGET_ID, cat.getId(), "ATTRAKCIONY", 100, false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.add(BUDGET_ID, cat.getId(), "attrakciony", 100, false));
        assertTrue(ex.getMessage().contains("Дети"), "error should name the category: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("attrakciony"), ex.getMessage());
    }

    @Test
    void differentPatternForTheSameCategoryIsAllowed() {
        CategoryRuleService service = newService();
        Category cat = category("Дети");
        stubCategory(cat);

        service.add(BUDGET_ID, cat.getId(), "ATTRAKCIONY", 100, false);

        assertDoesNotThrow(() -> service.add(BUDGET_ID, cat.getId(), "DRUGOI TEKST", 100, false));
    }

    @Test
    void samePatternForADifferentCategoryIsAllowed() {
        CategoryRuleService service = newService();
        Category kids = category("Дети");
        Category food = category("Продукты");
        stubCategory(kids);
        stubCategory(food);

        service.add(BUDGET_ID, kids.getId(), "SANTA", 100, false);

        assertDoesNotThrow(() -> service.add(BUDGET_ID, food.getId(), "SANTA", 100, false));
    }

    private final List<CategoryRule> saved = new ArrayList<>();
    private CategoryRuleRepository ruleRepository;
    private CategoryService categoryService;

    private CategoryRuleService newService() {
        ruleRepository = mock(CategoryRuleRepository.class);
        when(ruleRepository.findAllByBudgetId(BUDGET_ID)).thenAnswer(inv -> new ArrayList<>(saved));
        when(ruleRepository.save(any(CategoryRule.class))).thenAnswer(inv -> {
            CategoryRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            saved.add(r);
            return r;
        });

        BudgetRepository budgetRepository = mock(BudgetRepository.class);
        when(budgetRepository.findById(BUDGET_ID)).thenReturn(Optional.of(new Budget()));

        categoryService = mock(CategoryService.class);

        return new CategoryRuleService(ruleRepository, budgetRepository, categoryService, null);
    }

    private void stubCategory(Category c) {
        when(categoryService.findById(BUDGET_ID, c.getId())).thenReturn(Optional.of(c));
    }

    private Category category(String name) {
        Category c = new Category();
        c.setId(UUID.randomUUID());
        c.setKind(CategoryKind.EXPENSE);
        c.setName(name);
        return c;
    }
}

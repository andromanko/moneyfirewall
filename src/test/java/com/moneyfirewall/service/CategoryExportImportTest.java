package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.User;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class CategoryExportImportTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("moneyfirewall")
            .withUsername("moneyfirewall")
            .withPassword("moneyfirewall");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    UserService userService;

    @Autowired
    BudgetService budgetService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    CategoryRuleService categoryRuleService;

    @Test
    void exportsRulesAndImportSkipsExisting() {
        User user = userService.getOrCreate(333L, 333L, "u3");
        Budget budget = budgetService.createBudget(user.getId(), "b4");

        categoryRuleService.add(
                budget.getId(), CategoryKind.EXPENSE, "Продукты / Магазины",
                new CategoryRuleConditions("евроопт", null, null, null, false, 100), false);

        List<CategoryRuleService.CategoryRuleTransferEntry> exported = categoryRuleService.exportAll(budget.getId());
        Assertions.assertEquals(1, exported.size());
        Assertions.assertEquals("Продукты / Магазины", exported.get(0).category());
        Assertions.assertEquals("евроопт", exported.get(0).pattern());

        CategoryRuleService.CategoryRuleImportResult resultSame = categoryRuleService.importAll(budget.getId(), exported);
        Assertions.assertEquals(0, resultSame.created());
        Assertions.assertEquals(1, resultSame.skipped());

        Budget other = budgetService.createBudget(user.getId(), "b5");
        CategoryRuleService.CategoryRuleImportResult firstImport = categoryRuleService.importAll(other.getId(), exported);
        Assertions.assertEquals(1, firstImport.created());
        Assertions.assertEquals(0, firstImport.skipped());

        CategoryRuleService.CategoryRuleImportResult secondImport = categoryRuleService.importAll(other.getId(), exported);
        Assertions.assertEquals(0, secondImport.created());
        Assertions.assertEquals(1, secondImport.skipped());
    }

    @Test
    void exportsTreeAndImportSkipsExisting() {
        User user = userService.getOrCreate(222L, 222L, "u2");
        Budget budget = budgetService.createBudget(user.getId(), "b2");

        categoryService.ensureChild(budget.getId(), CategoryKind.EXPENSE, "Продукты", "Магазины");
        categoryService.ensureChild(budget.getId(), CategoryKind.EXPENSE, "Продукты", "Кафе");
        categoryService.ensure(budget.getId(), CategoryKind.INCOME, "Зарплата");
        categoryService.ensureCash(budget.getId());

        List<CategoryService.CategoryTransferEntry> exported = categoryService.exportAll(budget.getId());

        Assertions.assertTrue(exported.stream().noneMatch(e -> "CASH".equalsIgnoreCase(e.name())), "CASH must not be exported");
        CategoryService.CategoryTransferEntry products = exported.stream()
                .filter(e -> "Продукты".equals(e.name()))
                .findFirst().orElseThrow();
        Assertions.assertEquals("EXPENSE", products.kind());
        Assertions.assertEquals(List.of("Магазины", "Кафе"), products.children());

        // Re-importing the exact export into the same budget must create nothing new.
        CategoryService.CategoryImportResult resultSame = categoryService.importAll(budget.getId(), exported);
        Assertions.assertEquals(0, resultSame.created());
        Assertions.assertEquals(3, resultSame.skipped());

        // A fresh budget gets everything created once, then skipped on a second import.
        Budget other = budgetService.createBudget(user.getId(), "b3");
        CategoryService.CategoryImportResult firstImport = categoryService.importAll(other.getId(), exported);
        Assertions.assertEquals(3, firstImport.created());
        Assertions.assertEquals(0, firstImport.skipped());

        CategoryService.CategoryImportResult secondImport = categoryService.importAll(other.getId(), exported);
        Assertions.assertEquals(0, secondImport.created());
        Assertions.assertEquals(3, secondImport.skipped());
    }
}

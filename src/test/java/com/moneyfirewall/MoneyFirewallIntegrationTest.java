package com.moneyfirewall;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.ImportFileType;
import com.moneyfirewall.domain.User;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.service.BudgetService;
import com.moneyfirewall.service.ImportService;
import com.moneyfirewall.service.MerchantAliasService;
import com.moneyfirewall.service.UserService;
import java.nio.charset.StandardCharsets;
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
class MoneyFirewallIntegrationTest {
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
    ImportService importService;

    @Autowired
    MerchantAliasService merchantAliasService;

    @Autowired
    TransactionRepository transactionRepository;

    @Test
    void importIsIdempotentAndAppliesAliases() throws Exception {
        User user = userService.getOrCreate(111L, 111L, "u1");
        Budget budget = budgetService.createBudget(user.getId(), "b1");
        merchantAliasService.add(budget.getId(), "SANTA", "магазин Санта", 10, false);

        String json = """
                [
                  {"date":"2026-04-01","amount":-12.34,"currency":"BYN","direction":"EXPENSE","account":"Card1","counterparty":"SANTA","description":"shop"},
                  {"date":"2026-04-02","amount":100.00,"currency":"BYN","direction":"INCOME","account":"Card1","counterparty":"Salary","description":"pay"}
                ]
                """;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        ImportService.ImportResult r1 = importService.importFile(budget.getId(), user.getId(), "generic", ImportFileType.JSON, "file1", bytes);
        Assertions.assertFalse(r1.alreadyImported());
        Assertions.assertEquals(2, r1.inserted());

        ImportService.ImportResult r2 = importService.importFile(budget.getId(), user.getId(), "generic", ImportFileType.JSON, "file1", bytes);
        Assertions.assertTrue(r2.alreadyImported());

        long count = transactionRepository.findAll().stream().filter(t -> t.getBudget().getId().equals(budget.getId())).count();
        Assertions.assertEquals(2, count);

        String normalized = transactionRepository.findAll().stream()
                .filter(t -> "SANTA".equals(t.getCounterpartyRaw()))
                .findFirst()
                .map(t -> t.getCounterpartyNormalized())
                .orElse("");
        Assertions.assertEquals("магазин Санта", normalized);
    }
}


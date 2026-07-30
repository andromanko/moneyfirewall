package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.CategoryRule;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.CategoryRuleRepository;
import com.moneyfirewall.repo.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An INCOME transaction that matches no income rule but DOES match an existing expense-category
 * rule (e.g. a merchant refund) should be treated as money coming back for that expense, not new
 * income: flipped to a negative EXPENSE so every category/month SUMIFS formula nets it out
 * automatically, with no separate "refund" concept needed anywhere downstream.
 */
class CategoryRuleRefundTest {
    @Test
    void incomeMatchingAnExpenseRuleBecomesANegativeExpense() {
        UUID budgetId = UUID.randomUUID();
        Category kids = new Category();
        kids.setId(UUID.randomUUID());
        kids.setKind(CategoryKind.EXPENSE);
        kids.setName("Дети");

        CategoryRule expenseRule = new CategoryRule();
        expenseRule.setId(UUID.randomUUID());
        expenseRule.setCategory(kids);
        expenseRule.setPattern("ATTRAKCIONY");
        expenseRule.setPriority(100);

        Account account = new Account();
        account.setId(UUID.randomUUID());

        Transaction refund = new Transaction();
        refund.setId(UUID.randomUUID());
        refund.setDirection(TransactionDirection.INCOME);
        refund.setAmount(new BigDecimal("10"));
        refund.setCurrency("BYN");
        refund.setAccount(account);
        refund.setOccurredAt(Instant.parse("2026-07-16T19:57:45Z"));
        refund.setCounterpartyRaw("ATTRAKCIONY CHELYUSK");

        CategoryRuleRepository ruleRepository = mock(CategoryRuleRepository.class);
        when(ruleRepository.findAllByBudgetId(budgetId)).thenReturn(List.of(expenseRule));

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-01T00:00:00Z");
        when(transactionRepository.findAllInRange(budgetId, from, to)).thenReturn(List.of(refund));

        CategoryService categoryService = mock(CategoryService.class);
        when(categoryService.normalizeMtbankMinskCounterparty(any())).thenReturn("ATTRAKCIONY CHELYUSK");
        when(categoryService.isMtbankMinskOperation(any())).thenReturn(false);

        CategoryRuleService service = new CategoryRuleService(ruleRepository, null, categoryService, transactionRepository);

        int updated = service.recategorizeInPeriod(budgetId, from, to);

        assertEquals(1, updated);
        assertEquals(TransactionDirection.EXPENSE, refund.getDirection());
        assertEquals(0, new BigDecimal("-10").compareTo(refund.getAmount()));
        assertEquals(kids, refund.getCategory());
    }
}

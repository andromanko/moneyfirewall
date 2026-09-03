package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.AccountRepository;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.repo.TransferGroupRepository;
import com.moneyfirewall.repo.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A re-import bug (see ImportService's externalHash comment: an alias added between imports used
 * to change the dedup hash) leaves two rows with the same account/timestamp/amount/currency/
 * direction. That combination can never be a legitimate coincidence, so every such group is
 * collapsed to a single survivor: uncategorized copies go first when the group is mixed, and
 * whatever's left is further collapsed to the earliest-created row, since two rows tied on
 * everything but category disagree about which category is right, not about whether they're
 * duplicates.
 */
class TransactionServiceDedupTest {
    private static final UUID BUDGET_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-15T10:00:00Z");

    @Test
    void uncategorizedCopyOfAMixedGroupIsDeleted() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");

        Transaction categorized = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, category("Продукты"), t(1));
        Transaction uncategorized = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, null, t(2));
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(categorized, uncategorized));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(1, result.deleted());
        assertTrue(result.categoryConflicts().isEmpty());
        verify(repo, times(1)).delete(uncategorized);
        verify(repo, never()).delete(categorized);
    }

    @Test
    void differingAmountsAreNotTreatedAsDuplicates() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");

        Transaction a = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, null, t(1));
        Transaction b = tx(account, OCCURRED_AT, "150", "BYN", TransactionDirection.EXPENSE, null, t(2));
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(a, b));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(0, result.deleted());
        verify(repo, never()).delete(any());
    }

    @Test
    void bothCategorizedKeepsTheEarlierAndFlagsTheCategoryDifference() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");

        Transaction earlier = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, category("Продукты"), t(1));
        Transaction later = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, category("Транспорт"), t(2));
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(earlier, later));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(1, result.deleted());
        assertEquals(1, result.categoryConflicts().size());
        verify(repo, times(1)).delete(later);
        verify(repo, never()).delete(earlier);
    }

    @Test
    void bothUncategorizedKeepsTheEarlierWithoutFlaggingAConflict() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");

        Transaction earlier = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, null, t(1));
        Transaction later = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, null, t(2));
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(earlier, later));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(1, result.deleted());
        assertTrue(result.categoryConflicts().isEmpty());
        verify(repo, times(1)).delete(later);
        verify(repo, never()).delete(earlier);
    }

    @Test
    void differentScaleOfTheSameAmountStillMatches() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");

        Transaction categorized = tx(account, OCCURRED_AT, "100", "BYN", TransactionDirection.EXPENSE, category("Продукты"), t(1));
        Transaction uncategorized = tx(account, OCCURRED_AT, "100.00", "BYN", TransactionDirection.EXPENSE, null, t(2));
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(categorized, uncategorized));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(1, result.deleted());
    }

    private static Instant t(int secondsOffset) {
        return Instant.parse("2026-08-15T00:00:00Z").plusSeconds(secondsOffset);
    }

    private TransactionService newService(TransactionRepository repo) {
        return new TransactionService(
                mock(BudgetRepository.class),
                mock(AccountRepository.class),
                mock(UserRepository.class),
                repo,
                mock(TransferGroupRepository.class),
                mock(CategoryService.class),
                mock(MerchantAliasService.class)
        );
    }

    private Account account(String name) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        return a;
    }

    private Category category(String name) {
        Category c = new Category();
        c.setId(UUID.randomUUID());
        c.setName(name);
        return c;
    }

    private Transaction tx(Account account, Instant occurredAt, String amount, String currency, TransactionDirection direction, Category category, Instant createdAt) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAccount(account);
        t.setOccurredAt(occurredAt);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(currency);
        t.setDirection(direction);
        t.setCategory(category);
        t.setCreatedAt(createdAt);
        return t;
    }
}

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
 * direction but different categorization. That combination can never be a legitimate coincidence,
 * so the sweep treats it as a bug and drops the uncategorized copy — but only when categorization
 * actually splits the group; if it doesn't, the ambiguity is reported instead of guessed at.
 */
class TransactionServiceDedupTest {
    private static final UUID BUDGET_ID = UUID.randomUUID();

    @Test
    void uncategorizedCopyOfAnExactMatchIsDeleted() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");
        Instant occurredAt = Instant.parse("2026-08-15T10:00:00Z");

        Transaction categorized = tx(account, occurredAt, "100", "BYN", TransactionDirection.EXPENSE, category("Продукты"));
        Transaction uncategorized = tx(account, occurredAt, "100", "BYN", TransactionDirection.EXPENSE, null);
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(categorized, uncategorized));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(1, result.deleted());
        assertTrue(result.ambiguousGroups().isEmpty());
        verify(repo, times(1)).delete(uncategorized);
        verify(repo, never()).delete(categorized);
    }

    @Test
    void differingAmountsAreNotTreatedAsDuplicates() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");
        Instant occurredAt = Instant.parse("2026-08-15T10:00:00Z");

        Transaction a = tx(account, occurredAt, "100", "BYN", TransactionDirection.EXPENSE, null);
        Transaction b = tx(account, occurredAt, "150", "BYN", TransactionDirection.EXPENSE, null);
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(a, b));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(0, result.deleted());
        verify(repo, never()).delete(any());
    }

    @Test
    void bothCategorizedIsReportedAsAmbiguousNotDeleted() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");
        Instant occurredAt = Instant.parse("2026-08-15T10:00:00Z");

        Transaction a = tx(account, occurredAt, "100", "BYN", TransactionDirection.EXPENSE, category("Продукты"));
        Transaction b = tx(account, occurredAt, "100", "BYN", TransactionDirection.EXPENSE, category("Транспорт"));
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(a, b));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(0, result.deleted());
        assertEquals(1, result.ambiguousGroups().size());
        verify(repo, never()).delete(any());
    }

    @Test
    void differentScaleOfTheSameAmountStillMatches() {
        TransactionRepository repo = mock(TransactionRepository.class);
        Account account = account("Alfa");
        Instant occurredAt = Instant.parse("2026-08-15T10:00:00Z");

        Transaction categorized = tx(account, occurredAt, "100", "BYN", TransactionDirection.EXPENSE, category("Продукты"));
        Transaction uncategorized = tx(account, occurredAt, "100.00", "BYN", TransactionDirection.EXPENSE, null);
        when(repo.findAllForDuplicateScan(BUDGET_ID)).thenReturn(List.of(categorized, uncategorized));

        TransactionService.DedupResult result = newService(repo).deduplicateExactMatches(BUDGET_ID, null, null);

        assertEquals(1, result.deleted());
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

    private Transaction tx(Account account, Instant occurredAt, String amount, String currency, TransactionDirection direction, Category category) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAccount(account);
        t.setOccurredAt(occurredAt);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(currency);
        t.setDirection(direction);
        t.setCategory(category);
        return t;
    }
}

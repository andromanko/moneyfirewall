package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.domain.TransactionSource;
import com.moneyfirewall.domain.TransferGroup;
import com.moneyfirewall.domain.User;
import com.moneyfirewall.repo.AccountRepository;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.repo.TransferGroupRepository;
import com.moneyfirewall.repo.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {
    private final BudgetRepository budgetRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final TransferGroupRepository transferGroupRepository;
    private final CategoryService categoryService;
    private final MerchantAliasService merchantAliasService;

    public TransactionService(
            BudgetRepository budgetRepository,
            AccountRepository accountRepository,
            UserRepository userRepository,
            TransactionRepository transactionRepository,
            TransferGroupRepository transferGroupRepository,
            CategoryService categoryService,
            MerchantAliasService merchantAliasService
    ) {
        this.budgetRepository = budgetRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.transferGroupRepository = transferGroupRepository;
        this.categoryService = categoryService;
        this.merchantAliasService = merchantAliasService;
    }

    @Transactional
    public Transaction createIncome(UUID budgetId, UUID userId, Instant occurredAt, BigDecimal amount, String currency, String accountName, String categoryName, String counterparty, String description) {
        return createCategorized(budgetId, userId, occurredAt, amount, currency, accountName, categoryName, counterparty, description, TransactionDirection.INCOME, CategoryKind.INCOME);
    }

    @Transactional
    public Transaction createExpense(UUID budgetId, UUID userId, Instant occurredAt, BigDecimal amount, String currency, String accountName, String categoryName, String counterparty, String description) {
        return createCategorized(budgetId, userId, occurredAt, amount, currency, accountName, categoryName, counterparty, description, TransactionDirection.EXPENSE, CategoryKind.EXPENSE);
    }

    @Transactional
    public Transaction createIncomeByCategoryId(UUID budgetId, UUID userId, Instant occurredAt, BigDecimal amount, String currency, String accountName, UUID categoryId, String counterparty, String description) {
        return createWithCategoryId(budgetId, userId, occurredAt, amount, currency, accountName, categoryId, counterparty, description, TransactionDirection.INCOME);
    }

    @Transactional
    public Transaction createExpenseByCategoryId(UUID budgetId, UUID userId, Instant occurredAt, BigDecimal amount, String currency, String accountName, UUID categoryId, String counterparty, String description) {
        return createWithCategoryId(budgetId, userId, occurredAt, amount, currency, accountName, categoryId, counterparty, description, TransactionDirection.EXPENSE);
    }

    @Transactional
    public TransferGroup createTransfer(UUID budgetId, UUID userId, Instant occurredAt, BigDecimal amount, String currency, String fromAccountName, String toAccountName, String description) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        Account from = accountRepository.findByBudgetIdAndName(budgetId, fromAccountName).orElseThrow();
        Account to = accountRepository.findByBudgetIdAndName(budgetId, toAccountName).orElseThrow();

        TransferGroup group = new TransferGroup();
        group.setBudget(budget);
        group.setCreatedAt(Instant.now());
        TransferGroup savedGroup = transferGroupRepository.save(group);

        Transaction out = new Transaction();
        out.setBudget(budget);
        out.setUser(user);
        out.setDirection(TransactionDirection.TRANSFER);
        out.setOccurredAt(occurredAt);
        out.setAmount(amount);
        out.setCurrency(currency);
        out.setAccount(from);
        out.setCategory(null);
        out.setCounterpartyRaw(null);
        out.setCounterpartyNormalized(null);
        out.setDescription(description);
        out.setSource(TransactionSource.MANUAL);
        out.setExternalHash(null);
        out.setTransferGroup(savedGroup);
        out.setImportSession(null);
        out.setCreatedAt(Instant.now());
        transactionRepository.save(out);

        Transaction in = new Transaction();
        in.setBudget(budget);
        in.setUser(user);
        in.setDirection(TransactionDirection.TRANSFER);
        in.setOccurredAt(occurredAt);
        in.setAmount(amount);
        in.setCurrency(currency);
        in.setAccount(to);
        in.setCategory(null);
        in.setCounterpartyRaw(null);
        in.setCounterpartyNormalized(null);
        in.setDescription(description);
        in.setSource(TransactionSource.MANUAL);
        in.setExternalHash(null);
        in.setTransferGroup(savedGroup);
        in.setImportSession(null);
        in.setCreatedAt(Instant.now());
        transactionRepository.save(in);

        return savedGroup;
    }

    private Transaction createCategorized(
            UUID budgetId,
            UUID userId,
            Instant occurredAt,
            BigDecimal amount,
            String currency,
            String accountName,
            String categoryName,
            String counterparty,
            String description,
            TransactionDirection direction,
            CategoryKind categoryKind
    ) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findByBudgetIdAndName(budgetId, accountName).orElseThrow();
        Category category = categoryService.ensure(budgetId, categoryKind, categoryName);

        Transaction t = new Transaction();
        t.setBudget(budget);
        t.setUser(user);
        t.setDirection(direction);
        t.setOccurredAt(occurredAt);
        t.setAmount(amount);
        t.setCurrency(currency);
        t.setAccount(account);
        t.setCategory(category);
        t.setCounterpartyRaw(counterparty);
        t.setCounterpartyNormalized(merchantAliasService.normalize(budgetId, counterparty));
        t.setDescription(description);
        t.setSource(TransactionSource.MANUAL);
        t.setExternalHash(null);
        t.setTransferGroup(null);
        t.setImportSession(null);
        t.setCreatedAt(Instant.now());
        return transactionRepository.save(t);
    }

    private Transaction createWithCategoryId(
            UUID budgetId,
            UUID userId,
            Instant occurredAt,
            BigDecimal amount,
            String currency,
            String accountName,
            UUID categoryId,
            String counterparty,
            String description,
            TransactionDirection direction
    ) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findByBudgetIdAndName(budgetId, accountName).orElseThrow();
        Category category = categoryService.findById(budgetId, categoryId).orElseThrow();

        Transaction t = new Transaction();
        t.setBudget(budget);
        t.setUser(user);
        t.setDirection(direction);
        t.setOccurredAt(occurredAt);
        t.setAmount(amount);
        t.setCurrency(currency);
        t.setAccount(account);
        t.setCategory(category);
        t.setCounterpartyRaw(counterparty);
        t.setCounterpartyNormalized(merchantAliasService.normalize(budgetId, counterparty));
        t.setDescription(description);
        t.setSource(TransactionSource.MANUAL);
        t.setExternalHash(null);
        t.setTransferGroup(null);
        t.setImportSession(null);
        t.setCreatedAt(Instant.now());
        return transactionRepository.save(t);
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> findById(UUID budgetId, UUID transactionId) {
        return transactionRepository.findByIdAndBudgetId(transactionId, budgetId);
    }

    @Transactional(readOnly = true)
    public List<Transaction> searchByCounterparty(UUID budgetId, String query, int limit) {
        return transactionRepository.searchByCounterparty(budgetId, query).stream().limit(limit).toList();
    }

    @Transactional
    public Transaction setCategory(UUID budgetId, UUID transactionId, Category category) {
        Transaction t = transactionRepository.findByIdAndBudgetId(transactionId, budgetId).orElseThrow();
        if (t.getDirection() == TransactionDirection.TRANSFER) {
            throw new IllegalStateException("Нельзя задать категорию для перевода");
        }
        t.setCategory(category);
        return transactionRepository.save(t);
    }


    @Transactional
    public Transaction setTags(UUID budgetId, UUID transactionId, String tags) {
        Transaction t = transactionRepository.findByIdAndBudgetId(transactionId, budgetId).orElseThrow();
        t.setTags(tags == null || tags.isBlank() ? null : tags.trim());
        return transactionRepository.save(t);
    }

    @Transactional
    public void delete(UUID budgetId, UUID transactionId) {
        Transaction t = transactionRepository.findByIdAndBudgetId(transactionId, budgetId).orElseThrow();
        transactionRepository.delete(t);
    }

    /**
     * Same account, timestamp, amount, currency and direction is not a coincidence — it's a bug
     * (typically a re-import after a merchant alias or category rule changed the match, see
     * ImportService.externalHash). Every such group is collapsed to one survivor: if categorization
     * splits the group, the uncategorized copies go first; whatever is left is further collapsed to
     * the earliest-created row, since two rows tied on everything but category disagree about which
     * category is right, not about whether they're duplicates.
     */
    @Transactional
    public DedupResult deduplicateExactMatches(UUID budgetId, Instant from, Instant to) {
        List<Transaction> candidates = from == null || to == null
                ? transactionRepository.findAllForDuplicateScan(budgetId)
                : transactionRepository.findForDuplicateScan(budgetId, from, to);
        Map<DedupKey, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction t : candidates) {
            DedupKey key = new DedupKey(t.getAccount().getId(), t.getOccurredAt(), t.getAmount().stripTrailingZeros(), t.getCurrency(), t.getDirection());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<Transaction> toDelete = new ArrayList<>();
        List<String> categoryConflicts = new ArrayList<>();
        for (List<Transaction> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<Transaction> categorized = group.stream().filter(t -> t.getCategory() != null).toList();
            List<Transaction> uncategorized = group.stream().filter(t -> t.getCategory() == null).toList();
            List<Transaction> survivors = !categorized.isEmpty() && !uncategorized.isEmpty() ? categorized : group;
            if (!categorized.isEmpty() && !uncategorized.isEmpty()) {
                toDelete.addAll(uncategorized);
            }
            if (survivors.size() > 1) {
                Transaction keep = survivors.stream().min(Comparator.comparing(Transaction::getCreatedAt)).orElseThrow();
                List<Transaction> extras = survivors.stream().filter(t -> t != keep).toList();
                toDelete.addAll(extras);
                boolean categoryMismatch = extras.stream().anyMatch(t -> !categoryIdOf(t).equals(categoryIdOf(keep)));
                if (categoryMismatch) {
                    categoryConflicts.add(keep.getOccurredAt() + " " + keep.getAmount() + " " + keep.getCurrency()
                            + " " + keep.getAccount().getName() + " x" + survivors.size());
                }
            }
        }
        toDelete.forEach(transactionRepository::delete);
        return new DedupResult(toDelete.size(), categoryConflicts);
    }

    private static Optional<UUID> categoryIdOf(Transaction t) {
        return t.getCategory() == null ? Optional.empty() : Optional.of(t.getCategory().getId());
    }

    public record DedupResult(int deleted, List<String> categoryConflicts) {}

    private record DedupKey(UUID accountId, Instant occurredAt, BigDecimal amount, String currency, TransactionDirection direction) {}
}


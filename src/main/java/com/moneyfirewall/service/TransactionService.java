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
}


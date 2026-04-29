package com.moneyfirewall.service;

import com.moneyfirewall.domain.AssetEvent;
import com.moneyfirewall.domain.AssetEventType;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.User;
import com.moneyfirewall.repo.AssetEventRepository;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetEventService {
    private final AssetEventRepository assetEventRepository;
    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;
    private final TransactionService transactionService;
    private final CategoryService categoryService;

    public AssetEventService(
            AssetEventRepository assetEventRepository,
            BudgetRepository budgetRepository,
            UserRepository userRepository,
            TransactionService transactionService,
            CategoryService categoryService
    ) {
        this.assetEventRepository = assetEventRepository;
        this.budgetRepository = budgetRepository;
        this.userRepository = userRepository;
        this.transactionService = transactionService;
        this.categoryService = categoryService;
    }

    @Transactional
    public AssetEvent addEvent(
            UUID budgetId,
            UUID userId,
            Instant occurredAt,
            AssetEventType type,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal priceAmount,
            String priceCurrency,
            BigDecimal feeAmount,
            String feeCurrency,
            String description,
            String feeAccountName
    ) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        AssetEvent e = new AssetEvent();
        e.setBudget(budget);
        e.setUser(user);
        e.setOccurredAt(occurredAt);
        e.setEventType(type);
        e.setAssetSymbol(assetSymbol);
        e.setQuantity(quantity);
        e.setPriceAmount(priceAmount);
        e.setPriceCurrency(priceCurrency);
        e.setFeeAmount(feeAmount);
        e.setFeeCurrency(feeCurrency);
        e.setDescription(description);
        e.setCreatedAt(Instant.now());
        AssetEvent saved = assetEventRepository.save(e);

        if (feeAmount != null && feeAmount.signum() != 0 && feeCurrency != null && feeAccountName != null && !feeAccountName.isBlank()) {
            categoryService.ensure(budgetId, CategoryKind.EXPENSE, "Комиссии");
            transactionService.createExpense(budgetId, userId, occurredAt, feeAmount.abs(), feeCurrency, feeAccountName, "Комиссии", "FEE", description);
        }

        return saved;
    }
}


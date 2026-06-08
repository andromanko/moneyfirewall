package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.importing.SimplePdfStatementParser;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.CategoryRepository;
import com.moneyfirewall.repo.TransactionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    public static final String CASH = "CASH";
    public static final String MTBANK_MINSK_CATEGORY = SimplePdfStatementParser.MTBANK_MINSK_COUNTERPARTY;

    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public CategoryService(
            CategoryRepository categoryRepository,
            BudgetRepository budgetRepository,
            TransactionRepository transactionRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Category ensure(UUID budgetId, CategoryKind kind, String name) {
        return categoryRepository.findParentByBudgetIdAndKindAndName(budgetId, kind, name)
                .orElseGet(() -> {
                    Budget budget = budgetRepository.findById(budgetId).orElseThrow();
                    Category c = new Category();
                    c.setBudget(budget);
                    c.setParentCategory(null);
                    c.setKind(kind);
                    c.setName(name);
                    c.setCreatedAt(Instant.now());
                    return categoryRepository.save(c);
                });
    }

    @Transactional
    public Category ensureChild(UUID budgetId, CategoryKind kind, String parentName, String childName) {
        Category parent = ensure(budgetId, kind, parentName);
        return categoryRepository.findChildByBudgetIdAndKindAndParentIdAndName(budgetId, kind, parent.getId(), childName)
                .orElseGet(() -> {
                    Budget budget = budgetRepository.findById(budgetId).orElseThrow();
                    Category c = new Category();
                    c.setBudget(budget);
                    c.setParentCategory(parent);
                    c.setKind(kind);
                    c.setName(childName);
                    c.setCreatedAt(Instant.now());
                    return categoryRepository.save(c);
                });
    }

    @Transactional(readOnly = true)
    public List<Category> list(UUID budgetId, CategoryKind kind) {
        return categoryRepository.findAllByBudgetIdAndKind(budgetId, kind);
    }

    @Transactional(readOnly = true)
    public List<Category> listParents(UUID budgetId, CategoryKind kind) {
        return categoryRepository.findAllParentsByBudgetIdAndKind(budgetId, kind);
    }

    @Transactional(readOnly = true)
    public List<Category> listChildren(UUID budgetId, CategoryKind kind, UUID parentId) {
        return categoryRepository.findAllChildrenByBudgetIdAndKind(budgetId, kind, parentId);
    }

    @Transactional
    public Category ensureCash(UUID budgetId) {
        return ensure(budgetId, CategoryKind.EXPENSE, CASH);
    }

    @Transactional
    public void ensureStandardIncomeCategories(UUID budgetId) {
        ensure(budgetId, CategoryKind.INCOME, "Работа");
        ensure(budgetId, CategoryKind.INCOME, "Зарплата");
        ensure(budgetId, CategoryKind.INCOME, "кэшбэк");
        ensure(budgetId, CategoryKind.INCOME, "Прочее");
    }

    @Transactional(readOnly = true)
    public boolean isCash(Category category) {
        return category != null && CASH.equalsIgnoreCase(category.getName()) && category.getParentCategory() == null;
    }

    @Transactional(readOnly = true)
    public Optional<Category> findById(UUID budgetId, UUID categoryId) {
        return categoryRepository.findByIdAndBudgetId(categoryId, budgetId);
    }

    @Transactional(readOnly = true)
    public List<Category> listIncomeByUsage(UUID budgetId) {
        Map<UUID, Long> usage = new HashMap<>();
        for (Object[] row : transactionRepository.countIncomesByCategoryId(budgetId)) {
            usage.put((UUID) row[0], (Long) row[1]);
        }
        return list(budgetId, CategoryKind.INCOME).stream()
                .sorted(Comparator
                        .comparing((Category c) -> usage.getOrDefault(c.getId(), 0L)).reversed()
                        .thenComparing(c -> displayName(c).toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Category> listExpenseByUsage(UUID budgetId) {
        Map<UUID, Long> usage = new HashMap<>();
        for (Object[] row : transactionRepository.countExpensesByCategoryId(budgetId)) {
            usage.put((UUID) row[0], (Long) row[1]);
        }
        return list(budgetId, CategoryKind.EXPENSE).stream()
                .filter(c -> !isCash(c))
                .sorted(Comparator
                        .comparing((Category c) -> usage.getOrDefault(c.getId(), 0L)).reversed()
                        .thenComparing(c -> displayName(c).toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isMtbankMinskOperation(String counterparty) {
        if (counterparty == null || counterparty.isBlank()) {
            return false;
        }
        String cp = SimplePdfStatementParser.stripMtbankCardPrefix(counterparty.trim());
        return cp.toUpperCase(Locale.ROOT).contains("MTBANK MINSK");
    }

    @Transactional(readOnly = true)
    public String normalizeMtbankMinskCounterparty(String counterparty) {
        return SimplePdfStatementParser.canonicalizeMtbankCounterparty(counterparty);
    }

    @Transactional
    public Category ensureMtbankMinskCategory(UUID budgetId) {
        return ensure(budgetId, CategoryKind.EXPENSE, MTBANK_MINSK_CATEGORY);
    }

    @Transactional(readOnly = true)
    public Optional<Category> categoryForMtbankMinsk(UUID budgetId, String counterparty) {
        if (!isMtbankMinskOperation(counterparty)) {
            return Optional.empty();
        }
        return categoryRepository.findParentByBudgetIdAndKindAndName(budgetId, CategoryKind.EXPENSE, MTBANK_MINSK_CATEGORY);
    }

    @Transactional(readOnly = true)
    public String displayName(Category c) {
        if (c.getParentCategory() == null) {
            return c.getName();
        }
        return c.getParentCategory().getName() + " / " + c.getName();
    }

    @Transactional(readOnly = true)
    public Optional<Category> matchExpenseByName(UUID budgetId, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return Optional.empty();
        }
        for (Category c : list(budgetId, CategoryKind.EXPENSE)) {
            if (isCash(c)) {
                continue;
            }
            String path = c.getParentCategory() == null ? c.getName() : c.getParentCategory().getName() + " / " + c.getName();
            if (c.getName().equalsIgnoreCase(q) || path.equalsIgnoreCase(q)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}


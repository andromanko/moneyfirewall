package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.CategoryRule;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.CategoryRuleRepository;
import com.moneyfirewall.repo.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryRuleService {
    private final CategoryRuleRepository ruleRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryService categoryService;
    private final TransactionRepository transactionRepository;

    public CategoryRuleService(
            CategoryRuleRepository ruleRepository,
            BudgetRepository budgetRepository,
            CategoryService categoryService,
            TransactionRepository transactionRepository
    ) {
        this.ruleRepository = ruleRepository;
        this.budgetRepository = budgetRepository;
        this.categoryService = categoryService;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public Category match(UUID budgetId, String counterparty, CategoryKind kind) {
        if (counterparty == null || counterparty.isBlank()) {
            return null;
        }
        List<CategoryRule> rules = ruleRepository.findAllByBudgetId(budgetId);
        for (CategoryRule r : rules) {
            Category c = r.getCategory();
            if (c == null || c.getKind() != kind) {
                continue;
            }
            if (matchesPattern(r, counterparty) && r.getAccountName() == null
                    && r.getMinAmount() == null && r.getExactAmount() == null) {
                return c;
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Category matchTransaction(UUID budgetId, Transaction t) {
        return matchTransaction(budgetId, t, null);
    }

    @Transactional(readOnly = true)
    public Category matchTransaction(UUID budgetId, Transaction t, Set<String> oncePerMonthSlots) {
        if (t.getDirection() != TransactionDirection.INCOME && t.getDirection() != TransactionDirection.EXPENSE) {
            return null;
        }
        CategoryKind kind = t.getDirection() == TransactionDirection.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        String counterparty = t.getCounterpartyNormalized();
        if (counterparty == null || counterparty.isBlank()) {
            counterparty = t.getCounterpartyRaw();
        }
        List<CategoryRule> rules = ruleRepository.findAllByBudgetId(budgetId);
        for (CategoryRule r : rules) {
            Category c = r.getCategory();
            if (c == null || c.getKind() != kind) {
                continue;
            }
            if (!matchesRule(budgetId, r, t, counterparty, oncePerMonthSlots)) {
                continue;
            }
            return c;
        }
        if (kind == CategoryKind.EXPENSE) {
            return categoryService.categoryForMtbankMinsk(budgetId, counterparty).orElse(null);
        }
        return null;
    }

    @Transactional
    public CategoryRule add(UUID budgetId, CategoryKind kind, String categoryName, String pattern, int priority, boolean isRegex) {
        return add(budgetId, kind, categoryName, new CategoryRuleConditions(pattern, null, null, null, false, priority), isRegex);
    }

    @Transactional
    public CategoryRule add(
            UUID budgetId,
            CategoryKind kind,
            String categoryName,
            CategoryRuleConditions conditions,
            boolean isRegex
    ) {
        if (!conditions.hasConstraints()) {
            throw new IllegalArgumentException("Rule needs pattern or account/amount constraints");
        }
        Category cat;
        int sep = categoryName.indexOf(" / ");
        if (sep > 0) {
            cat = categoryService.ensureChild(budgetId, kind, categoryName.substring(0, sep).trim(), categoryName.substring(sep + 3).trim());
        } else {
            cat = categoryService.ensure(budgetId, kind, categoryName);
        }
        return saveRule(budgetId, cat, conditions, conditions.priority(), isRegex);
    }

    @Transactional
    public CategoryRule add(UUID budgetId, UUID categoryId, String pattern, int priority, boolean isRegex) {
        Category cat = categoryService.findById(budgetId, categoryId).orElseThrow();
        return saveRule(budgetId, cat, new CategoryRuleConditions(pattern, null, null, null, false, priority), priority, isRegex);
    }

    private CategoryRule saveRule(UUID budgetId, Category cat, CategoryRuleConditions conditions, int priority, boolean isRegex) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        CategoryRule r = new CategoryRule();
        r.setBudget(budget);
        r.setCategory(cat);
        r.setPattern(conditions.pattern() == null ? "" : conditions.pattern().trim());
        r.setAccountName(blankToNull(conditions.accountName()));
        r.setMinAmount(conditions.minAmount());
        r.setExactAmount(conditions.exactAmount());
        r.setOncePerMonth(conditions.oncePerMonth());
        r.setPriority(priority);
        r.setRegex(isRegex);
        r.setCreatedAt(Instant.now());
        return ruleRepository.save(r);
    }

    @Transactional(readOnly = true)
    public List<CategoryRule> list(UUID budgetId) {
        return ruleRepository.findAllByBudgetId(budgetId);
    }

    @Transactional(readOnly = true)
    public Optional<CategoryRule> find(UUID budgetId, UUID id) {
        return ruleRepository.findByIdWithCategory(id).filter(r -> r.getBudget().getId().equals(budgetId));
    }

    @Transactional(readOnly = true)
    public List<CategoryRule> listByCategory(UUID budgetId, UUID categoryId) {
        return ruleRepository.findAllByBudgetIdAndCategoryId(budgetId, categoryId);
    }

    @Transactional
    public void delete(UUID id) {
        ruleRepository.deleteById(id);
    }

    @Transactional
    public int recategorizeExpenses(UUID budgetId, Instant from, Instant to) {
        return recategorizeInPeriod(budgetId, from, to);
    }

    @Transactional
    public int recategorizeInPeriod(UUID budgetId, Instant from, Instant to) {
        List<Transaction> tx = transactionRepository.findAllInRange(budgetId, from, to).stream()
                .filter(t -> t.getTransferGroup() == null)
                .filter(t -> t.getDirection() == TransactionDirection.INCOME || t.getDirection() == TransactionDirection.EXPENSE)
                .sorted(Comparator.comparing(Transaction::getOccurredAt))
                .toList();
        Set<String> oncePerMonthSlots = new HashSet<>();
        int updated = 0;
        for (Transaction t : tx) {
            if (t.getCounterpartyRaw() != null) {
                String normalized = categoryService.normalizeMtbankMinskCounterparty(t.getCounterpartyRaw());
                if (!normalized.equals(t.getCounterpartyNormalized())) {
                    t.setCounterpartyNormalized(normalized);
                }
            }
            Category matched = matchTransaction(budgetId, t, oncePerMonthSlots);
            if (matched == null && t.getDirection() == TransactionDirection.EXPENSE
                    && categoryService.isMtbankMinskOperation(t.getCounterpartyRaw())) {
                matched = categoryService.ensureMtbankMinskCategory(budgetId);
            }
            if (matched == null) {
                continue;
            }
            if (t.getCategory() == null || !matched.getId().equals(t.getCategory().getId())) {
                t.setCategory(matched);
                updated++;
            }
            reserveOncePerMonthSlot(budgetId, t, matched, oncePerMonthSlots);
        }
        return updated;
    }

    private boolean matchesRule(
            UUID budgetId,
            CategoryRule r,
            Transaction t,
            String counterparty,
            Set<String> oncePerMonthSlots
    ) {
        if (!matchesAccount(r, t)) {
            return false;
        }
        if (!matchesAmount(r, t.getAmount())) {
            return false;
        }
        if (!matchesPattern(r, counterparty)) {
            return false;
        }
        if (r.isOncePerMonth() && oncePerMonthSlotTaken(budgetId, r, t, oncePerMonthSlots)) {
            return false;
        }
        return true;
    }

    private boolean matchesAccount(CategoryRule r, Transaction t) {
        if (r.getAccountName() == null || r.getAccountName().isBlank()) {
            return true;
        }
        if (t.getAccount() == null) {
            return false;
        }
        return t.getAccount().getName().equalsIgnoreCase(r.getAccountName().trim());
    }

    private boolean matchesAmount(CategoryRule r, BigDecimal amount) {
        if (r.getMinAmount() != null && amount.compareTo(r.getMinAmount()) <= 0) {
            return false;
        }
        if (r.getExactAmount() != null && amount.compareTo(r.getExactAmount()) != 0) {
            return false;
        }
        return true;
    }

    private boolean matchesPattern(CategoryRule r, String counterparty) {
        if (r.getPattern() == null || r.getPattern().isBlank()) {
            return true;
        }
        if (counterparty == null || counterparty.isBlank()) {
            return false;
        }
        if (r.isRegex()) {
            return Pattern.compile(r.getPattern(), Pattern.CASE_INSENSITIVE).matcher(counterparty).find();
        }
        return counterparty.toLowerCase(Locale.ROOT).contains(r.getPattern().toLowerCase(Locale.ROOT));
    }

    private boolean oncePerMonthSlotTaken(UUID budgetId, CategoryRule r, Transaction t, Set<String> oncePerMonthSlots) {
        String slot = oncePerMonthSlotKey(r, t);
        if (oncePerMonthSlots != null && oncePerMonthSlots.contains(slot)) {
            return true;
        }
        YearMonth ym = YearMonth.from(t.getOccurredAt().atZone(ZoneOffset.UTC));
        Instant monthFrom = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant monthTo = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        UUID excludeId = t.getId() == null ? UUID.randomUUID() : t.getId();
        return transactionRepository.existsOncePerMonthMatch(
                budgetId,
                excludeId,
                r.getCategory().getId(),
                r.getAccountName(),
                r.getExactAmount(),
                monthFrom,
                monthTo
        );
    }

    private void reserveOncePerMonthSlot(UUID budgetId, Transaction t, Category matched, Set<String> oncePerMonthSlots) {
        if (oncePerMonthSlots == null) {
            return;
        }
        for (CategoryRule r : ruleRepository.findAllByBudgetId(budgetId)) {
            if (!r.isOncePerMonth() || r.getCategory() == null || !r.getCategory().getId().equals(matched.getId())) {
                continue;
            }
            String counterparty = t.getCounterpartyNormalized();
            if (counterparty == null || counterparty.isBlank()) {
                counterparty = t.getCounterpartyRaw();
            }
            if (matchesAccount(r, t) && matchesAmount(r, t.getAmount()) && matchesPattern(r, counterparty)) {
                oncePerMonthSlots.add(oncePerMonthSlotKey(r, t));
            }
        }
    }

    private String oncePerMonthSlotKey(CategoryRule r, Transaction t) {
        YearMonth ym = YearMonth.from(t.getOccurredAt().atZone(ZoneOffset.UTC));
        return r.getId() + "|" + ym;
    }

    private String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    @Transactional(readOnly = true)
    public List<CategoryRuleTransferEntry> exportAll(UUID budgetId) {
        List<CategoryRuleTransferEntry> result = new ArrayList<>();
        for (CategoryRule r : list(budgetId)) {
            Category c = r.getCategory();
            if (c == null) {
                continue;
            }
            result.add(new CategoryRuleTransferEntry(
                    c.getKind().name(),
                    categoryPath(c),
                    r.getPattern(),
                    r.isRegex(),
                    r.getPriority(),
                    r.getAccountName(),
                    r.getMinAmount(),
                    r.getExactAmount(),
                    r.isOncePerMonth()
            ));
        }
        return result;
    }

    @Transactional
    public CategoryRuleImportResult importAll(UUID budgetId, List<CategoryRuleTransferEntry> entries) {
        int created = 0;
        int skipped = 0;
        List<CategoryRule> existing = new ArrayList<>(list(budgetId));
        for (CategoryRuleTransferEntry entry : entries) {
            if (entry == null || entry.category() == null || entry.category().isBlank() || entry.kind() == null) {
                continue;
            }
            CategoryKind kind;
            try {
                kind = CategoryKind.valueOf(entry.kind().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            CategoryRuleConditions conditions = new CategoryRuleConditions(
                    entry.pattern() == null ? "" : entry.pattern(),
                    entry.accountName(),
                    entry.minAmount(),
                    entry.exactAmount(),
                    entry.oncePerMonth(),
                    entry.priority()
            );
            if (!conditions.hasConstraints()) {
                skipped++;
                continue;
            }
            boolean duplicate = existing.stream().anyMatch(r -> sameRule(r, kind, entry.category(), entry.isRegex(), conditions));
            if (duplicate) {
                skipped++;
                continue;
            }
            CategoryRule saved = add(budgetId, kind, entry.category(), conditions, entry.isRegex());
            existing.add(saved);
            created++;
        }
        return new CategoryRuleImportResult(created, skipped);
    }

    private boolean sameRule(CategoryRule r, CategoryKind kind, String categoryPath, boolean isRegex, CategoryRuleConditions c) {
        Category cat = r.getCategory();
        if (cat == null || cat.getKind() != kind) {
            return false;
        }
        if (!categoryPath(cat).equalsIgnoreCase(categoryPath.trim())) {
            return false;
        }
        if (r.isRegex() != isRegex) {
            return false;
        }
        if (!nullToEmpty(r.getPattern()).trim().equalsIgnoreCase(nullToEmpty(c.pattern()).trim())) {
            return false;
        }
        if (!Objects.equals(blankToNull(r.getAccountName()), blankToNull(c.accountName()))) {
            return false;
        }
        if (!amountsEqual(r.getMinAmount(), c.minAmount()) || !amountsEqual(r.getExactAmount(), c.exactAmount())) {
            return false;
        }
        return r.isOncePerMonth() == c.oncePerMonth();
    }

    private boolean amountsEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String categoryPath(Category c) {
        return c.getParentCategory() == null ? c.getName() : c.getParentCategory().getName() + " / " + c.getName();
    }

    public record CategoryRuleTransferEntry(
            String kind,
            String category,
            String pattern,
            boolean isRegex,
            int priority,
            String accountName,
            BigDecimal minAmount,
            BigDecimal exactAmount,
            boolean oncePerMonth
    ) {
    }

    public record CategoryRuleImportResult(int created, int skipped) {
    }
}

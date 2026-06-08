package com.moneyfirewall.service;

import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.reporting.ReportTables;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    public static final String BY_CATEGORY_SUBTOTAL = "ИТОГО";

    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final MerchantAliasService merchantAliasService;

    public ReportService(
            TransactionRepository transactionRepository,
            CategoryService categoryService,
            MerchantAliasService merchantAliasService
    ) {
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.merchantAliasService = merchantAliasService;
    }

    @Transactional(readOnly = true)
    public ReportTables build(UUID budgetId, Instant from, Instant to) {
        List<Transaction> tx = transactionRepository.findAllInRange(budgetId, from, to);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;

        Map<CategoryKey, BigDecimal> byCategoryAndCounterparty = new HashMap<>();
        Map<CategorySlot, BigDecimal> byCategoryTotal = new HashMap<>();
        Map<String, BigDecimal> byMember = new HashMap<>();

        List<List<Object>> txRows = new ArrayList<>();
        txRows.add(List.of("occurredAt", "direction", "amount", "currency", "account", "category", "subcategory", "nickname", "member", "isTransfer"));

        for (Transaction t : tx) {
            boolean isTransfer = t.getDirection() == TransactionDirection.TRANSFER || t.getTransferGroup() != null;
            Category category = t.getCategory();
            CategoryColumns cols = categoryColumns(budgetId, category);
            String member = t.getUser() == null ? "" : t.getUser().getDisplayName();
            String account = t.getAccount() == null ? "" : t.getAccount().getName();
            String cp = merchantAliasService.resolveDisplayName(budgetId, t.getCounterpartyRaw(), t.getCounterpartyNormalized());

            if (!isTransfer) {
                if (t.getDirection() == TransactionDirection.INCOME) {
                    income = income.add(t.getAmount());
                } else if (t.getDirection() == TransactionDirection.EXPENSE) {
                    if (!categoryService.isCash(category)) {
                        expense = expense.add(t.getAmount());
                        if ("Комиссии".equalsIgnoreCase(cols.category()) || "FEE".equalsIgnoreCase(cp)) {
                            fees = fees.add(t.getAmount());
                        }
                        String nicknameKey = cp.isBlank() ? "" : cp;
                        CategoryKey key = new CategoryKey(cols.category(), cols.subcategory(), nicknameKey);
                        byCategoryAndCounterparty.merge(key, t.getAmount(), BigDecimal::add);
                        byCategoryTotal.merge(new CategorySlot(cols.category(), cols.subcategory()), t.getAmount(), BigDecimal::add);
                        if (!member.isBlank()) {
                            byMember.merge(member, t.getAmount(), BigDecimal::add);
                        }
                    }
                }
            }

            txRows.add(List.of(
                    t.getOccurredAt().toString(),
                    t.getDirection().name(),
                    t.getAmount(),
                    t.getCurrency(),
                    account,
                    cols.category(),
                    cols.subcategory(),
                    cp,
                    member,
                    isTransfer
            ));
        }

        List<List<Object>> summary = buildSummarySheet(income, expense, fees, byCategoryTotal);

        List<List<Object>> catRows = buildByCategorySheet(byCategoryAndCounterparty);

        List<List<Object>> memberRows = new ArrayList<>();
        memberRows.add(List.of("member", "expense"));
        byMember.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> memberRows.add(List.of(e.getKey(), e.getValue())));

        return new ReportTables(summary, catRows, memberRows, txRows);
    }

    private List<List<Object>> buildSummarySheet(
            BigDecimal income,
            BigDecimal expense,
            BigDecimal fees,
            Map<CategorySlot, BigDecimal> byCategoryTotal
    ) {
        List<List<Object>> summary = new ArrayList<>();
        summary.add(List.of("metric", "value"));
        summary.add(List.of("income", income));
        summary.add(List.of("expense", expense));
        summary.add(List.of("net", income.subtract(expense)));
        summary.add(List.of("fees", fees));
        summary.add(List.of());
        summary.add(List.of("category", "subcategory", "expense"));
        byCategoryTotal.entrySet().stream()
                .sorted(Map.Entry.<CategorySlot, BigDecimal>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().category())
                        .thenComparing(e -> e.getKey().subcategory()))
                .forEach(e -> summary.add(List.of(
                        e.getKey().category(),
                        e.getKey().subcategory(),
                        e.getValue()
                )));
        return summary;
    }

    private List<List<Object>> buildByCategorySheet(Map<CategoryKey, BigDecimal> amounts) {
        List<List<Object>> catRows = new ArrayList<>();
        catRows.add(List.of("category", "subcategory", "nickname", "amount"));

        List<Map.Entry<CategoryKey, BigDecimal>> sorted = amounts.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<CategoryKey, BigDecimal> e) -> e.getKey().category())
                        .thenComparing(e -> e.getKey().subcategory())
                        .thenComparing(e -> e.getKey().nickname()))
                .toList();

        String currentCategory = null;
        BigDecimal categoryTotal = BigDecimal.ZERO;
        for (Map.Entry<CategoryKey, BigDecimal> e : sorted) {
            String category = e.getKey().category();
            if (currentCategory != null && !currentCategory.equals(category)) {
                catRows.add(List.of(currentCategory, "", BY_CATEGORY_SUBTOTAL, categoryTotal));
                categoryTotal = BigDecimal.ZERO;
            }
            currentCategory = category;
            categoryTotal = categoryTotal.add(e.getValue());
            catRows.add(List.of(
                    category,
                    e.getKey().subcategory(),
                    e.getKey().nickname(),
                    e.getValue()
            ));
        }
        if (currentCategory != null) {
            catRows.add(List.of(currentCategory, "", BY_CATEGORY_SUBTOTAL, categoryTotal));
        }

        return catRows;
    }

    private CategoryColumns categoryColumns(UUID budgetId, Category category) {
        if (category == null) {
            return new CategoryColumns("Uncategorized", "");
        }
        if (category.getParentCategory() != null) {
            return new CategoryColumns(category.getParentCategory().getName(), category.getName());
        }
        for (Category parent : categoryService.listParents(budgetId, CategoryKind.EXPENSE)) {
            if (categoryService.isCash(parent)) {
                continue;
            }
            for (Category child : categoryService.listChildren(budgetId, CategoryKind.EXPENSE, parent.getId())) {
                if (child.getId().equals(category.getId())) {
                    return new CategoryColumns(parent.getName(), child.getName());
                }
            }
        }
        return new CategoryColumns(category.getName(), "");
    }

    private record CategoryColumns(String category, String subcategory) {
    }

    private record CategorySlot(String category, String subcategory) {
    }

    private record CategoryKey(String category, String subcategory, String nickname) {
    }
}

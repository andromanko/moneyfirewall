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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    public static final String BY_CATEGORY_SUBTOTAL = "ИТОГО";
    public static final String BY_SUBCATEGORY_SUBTOTAL = "Подытог";

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

        Map<String, BigDecimal> incomeByCurrency = new TreeMap<>();
        Map<String, BigDecimal> expenseByCurrency = new TreeMap<>();
        Map<String, BigDecimal> feesByCurrency = new TreeMap<>();

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
            String currency = t.getCurrency() == null ? "" : t.getCurrency();

            if (!isTransfer) {
                if (t.getDirection() == TransactionDirection.INCOME) {
                    incomeByCurrency.merge(currency, t.getAmount(), BigDecimal::add);
                } else if (t.getDirection() == TransactionDirection.EXPENSE) {
                    if (!categoryService.isCash(category)) {
                        expenseByCurrency.merge(currency, t.getAmount(), BigDecimal::add);
                        if ("Комиссии".equalsIgnoreCase(cols.category()) || "FEE".equalsIgnoreCase(cp)) {
                            feesByCurrency.merge(currency, t.getAmount(), BigDecimal::add);
                        }
                        String nicknameKey = cp.isBlank() ? "" : cp;
                        CategoryKey key = new CategoryKey(cols.category(), cols.subcategory(), currency, nicknameKey);
                        byCategoryAndCounterparty.merge(key, t.getAmount(), BigDecimal::add);
                        byCategoryTotal.merge(new CategorySlot(cols.category(), cols.subcategory(), currency), t.getAmount(), BigDecimal::add);
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

        List<List<Object>> summary = buildSummarySheet(incomeByCurrency, expenseByCurrency, feesByCurrency, byCategoryTotal);

        List<List<Object>> catRows = buildByCategorySheet(byCategoryAndCounterparty);

        List<List<Object>> memberRows = new ArrayList<>();
        memberRows.add(List.of("member", "expense"));
        byMember.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> memberRows.add(List.of(e.getKey(), e.getValue())));

        return new ReportTables(summary, catRows, memberRows, txRows);
    }

    private List<List<Object>> buildSummarySheet(
            Map<String, BigDecimal> incomeByCurrency,
            Map<String, BigDecimal> expenseByCurrency,
            Map<String, BigDecimal> feesByCurrency,
            Map<CategorySlot, BigDecimal> byCategoryTotal
    ) {
        List<List<Object>> summary = new ArrayList<>();
        summary.add(List.of("metric", "currency", "value"));

        TreeSet<String> currencies = new TreeSet<>();
        currencies.addAll(incomeByCurrency.keySet());
        currencies.addAll(expenseByCurrency.keySet());
        currencies.addAll(feesByCurrency.keySet());
        for (String currency : currencies) {
            BigDecimal income = incomeByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal expense = expenseByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal fees = feesByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            summary.add(List.of("income", currency, income));
            summary.add(List.of("expense", currency, expense));
            summary.add(List.of("net", currency, income.subtract(expense)));
            summary.add(List.of("fees", currency, fees));
        }

        summary.add(List.of());
        summary.add(List.of("category", "subcategory", "currency", "expense"));
        byCategoryTotal.entrySet().stream()
                .sorted(Map.Entry.<CategorySlot, BigDecimal>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().category())
                        .thenComparing(e -> e.getKey().subcategory())
                        .thenComparing(e -> e.getKey().currency()))
                .forEach(e -> summary.add(List.of(
                        e.getKey().category(),
                        e.getKey().subcategory(),
                        e.getKey().currency(),
                        e.getValue()
                )));
        return summary;
    }

    private List<List<Object>> buildByCategorySheet(Map<CategoryKey, BigDecimal> amounts) {
        List<List<Object>> catRows = new ArrayList<>();
        catRows.add(List.of("category", "subcategory", "currency", "nickname", "amount"));

        List<Map.Entry<CategoryKey, BigDecimal>> sorted = amounts.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<CategoryKey, BigDecimal> e) -> e.getKey().category())
                        .thenComparing(e -> e.getKey().subcategory())
                        .thenComparing(e -> e.getKey().currency())
                        .thenComparing(e -> e.getKey().nickname()))
                .toList();

        String currentCategory = null;
        String currentSubcategory = null;
        Map<String, BigDecimal> subcategoryTotals = new TreeMap<>();
        Map<String, BigDecimal> categoryTotals = new TreeMap<>();

        for (Map.Entry<CategoryKey, BigDecimal> e : sorted) {
            String category = e.getKey().category();
            String subcategory = e.getKey().subcategory();
            String currency = e.getKey().currency();

            boolean categoryChanged = currentCategory != null && !currentCategory.equals(category);
            boolean subcategoryChanged = currentSubcategory != null
                    && (categoryChanged || !currentSubcategory.equals(subcategory));

            if (subcategoryChanged) {
                flushSubcategoryTotals(catRows, currentCategory, currentSubcategory, subcategoryTotals);
            }
            if (categoryChanged) {
                flushCategoryTotals(catRows, currentCategory, categoryTotals);
            }

            currentCategory = category;
            currentSubcategory = subcategory;
            subcategoryTotals.merge(currency, e.getValue(), BigDecimal::add);
            categoryTotals.merge(currency, e.getValue(), BigDecimal::add);

            catRows.add(List.of(category, subcategory, currency, e.getKey().nickname(), e.getValue()));
        }
        if (currentCategory != null) {
            flushSubcategoryTotals(catRows, currentCategory, currentSubcategory, subcategoryTotals);
            flushCategoryTotals(catRows, currentCategory, categoryTotals);
        }

        return catRows;
    }

    private void flushSubcategoryTotals(
            List<List<Object>> catRows,
            String category,
            String subcategory,
            Map<String, BigDecimal> totals
    ) {
        if (!subcategory.isBlank()) {
            totals.forEach((currency, total) ->
                    catRows.add(List.of(category, subcategory, currency, BY_SUBCATEGORY_SUBTOTAL, total)));
        }
        totals.clear();
    }

    private void flushCategoryTotals(List<List<Object>> catRows, String category, Map<String, BigDecimal> totals) {
        totals.forEach((currency, total) ->
                catRows.add(List.of(category, "", currency, BY_CATEGORY_SUBTOTAL, total)));
        totals.clear();
    }

    /**
     * Computes nested row ranges (0-based, inclusive) for the "ByCategory" sheet so that
     * detail rows can be collapsed/expanded per category (level 1) and per subcategory (level 2)
     * without merging any cells.
     */
    public static List<RowRange> computeByCategoryOutline(List<List<Object>> byCategoryRows) {
        List<RowRange> ranges = new ArrayList<>();
        if (byCategoryRows.isEmpty()) {
            return ranges;
        }
        int nicknameIdx = byCategoryRows.getFirst().indexOf("nickname");
        if (nicknameIdx < 0) {
            return ranges;
        }

        int categoryBlockStart = 1;
        int subcategoryBlockStart = 1;
        for (int r = 1; r < byCategoryRows.size(); r++) {
            List<Object> row = byCategoryRows.get(r);
            Object nickname = nicknameIdx < row.size() ? row.get(nicknameIdx) : null;
            String nn = String.valueOf(nickname);
            if (BY_SUBCATEGORY_SUBTOTAL.equals(nn)) {
                if (subcategoryBlockStart <= r - 1) {
                    ranges.add(new RowRange(subcategoryBlockStart, r - 1, 2));
                }
                subcategoryBlockStart = r + 1;
            } else if (BY_CATEGORY_SUBTOTAL.equals(nn)) {
                if (categoryBlockStart <= r - 1) {
                    ranges.add(new RowRange(categoryBlockStart, r - 1, 1));
                }
                categoryBlockStart = r + 1;
                subcategoryBlockStart = r + 1;
            }
        }
        return ranges;
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

    private record CategorySlot(String category, String subcategory, String currency) {
    }

    private record CategoryKey(String category, String subcategory, String currency, String nickname) {
    }

    public record RowRange(int startRow, int endRowInclusive, int level) {
    }
}

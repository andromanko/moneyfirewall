package com.moneyfirewall.service;

import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.reporting.FormulaCell;
import com.moneyfirewall.reporting.ReportTables;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    public static final String BY_CATEGORY_SUBTOTAL = "ИТОГО";
    public static final String BY_SUBCATEGORY_SUBTOTAL = "Подытог";
    private static final String FEE_CATEGORY_NAME = "Комиссии";
    private static final String FEE_NICKNAME = "FEE";
    /** Bounded row count used for cross-sheet SUMIFS/SUMPRODUCT ranges instead of whole-column
     * references, which POI's formula evaluator would otherwise try to materialize in full. */
    private static final int TX_DATA_LAST_ROW = 100_000;

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
                        if (FEE_CATEGORY_NAME.equalsIgnoreCase(cols.category()) || FEE_NICKNAME.equalsIgnoreCase(cp)) {
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

        return new ReportTables(summary, catRows, memberRows, txRows, incomeByCurrency, expenseByCurrency);
    }

    static List<List<Object>> buildSummarySheet(
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
            int incomeRow = summary.size() + 1;
            summary.add(List.of("income", currency, incomeFormula(currency)));
            int expenseRow = summary.size() + 1;
            summary.add(List.of("expense", currency, expenseFormula(currency)));
            summary.add(List.of("net", currency, new FormulaCell("C" + incomeRow + "-C" + expenseRow)));
            summary.add(List.of("fees", currency, feesFormula(currency)));
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
                        categoryExpenseFormula(e.getKey().category(), e.getKey().subcategory(), e.getKey().currency())
                )));
        return summary;
    }

    private static String txRange(String column) {
        return "Transactions!" + column + "2:" + column + TX_DATA_LAST_ROW;
    }

    private static String escapeFormulaString(String s) {
        return s == null ? "" : s.replace("\"", "\"\"");
    }

    private static FormulaCell incomeFormula(String currency) {
        return new FormulaCell("SUMIFS(" + txRange("C") + "," + txRange("B") + ",\"INCOME\","
                + txRange("D") + ",\"" + escapeFormulaString(currency) + "\"," + txRange("J") + ",FALSE)");
    }

    private static FormulaCell expenseFormula(String currency) {
        return new FormulaCell("SUMIFS(" + txRange("C") + "," + txRange("B") + ",\"EXPENSE\","
                + txRange("D") + ",\"" + escapeFormulaString(currency) + "\"," + txRange("J") + ",FALSE,"
                + txRange("F") + ",\"<>CASH\")");
    }

    private static FormulaCell feesFormula(String currency) {
        // Two SUMIFS added together instead of one SUMPRODUCT-with-OR: POI's SUMPRODUCT chokes on
        // mixing a boolean-literal range comparison (isTransfer=FALSE) into the array arithmetic.
        // The two conditions (fee category vs. fee nickname) are mutually exclusive by construction
        // (a transaction's category can't be both "Комиссии" and "<>Комиссии"), so no double-counting.
        String byCategory = "SUMIFS(" + txRange("C") + "," + txRange("B") + ",\"EXPENSE\","
                + txRange("D") + ",\"" + escapeFormulaString(currency) + "\"," + txRange("J") + ",FALSE,"
                + txRange("F") + ",\"" + escapeFormulaString(FEE_CATEGORY_NAME) + "\")";
        String byNickname = "SUMIFS(" + txRange("C") + "," + txRange("B") + ",\"EXPENSE\","
                + txRange("D") + ",\"" + escapeFormulaString(currency) + "\"," + txRange("J") + ",FALSE,"
                + txRange("F") + ",\"<>" + escapeFormulaString(FEE_CATEGORY_NAME) + "\","
                + txRange("H") + ",\"" + FEE_NICKNAME + "\")";
        return new FormulaCell(byCategory + "+" + byNickname);
    }

    private static FormulaCell categoryExpenseFormula(String category, String subcategory, String currency) {
        return new FormulaCell("SUMIFS(" + txRange("C") + "," + txRange("F") + ",\"" + escapeFormulaString(category)
                + "\"," + txRange("G") + ",\"" + escapeFormulaString(subcategory) + "\"," + txRange("D") + ",\""
                + escapeFormulaString(currency) + "\"," + txRange("B") + ",\"EXPENSE\"," + txRange("J") + ",FALSE)");
    }

    static List<List<Object>> buildByCategorySheet(Map<CategoryKey, BigDecimal> amounts) {
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
        int categoryBlockStart = 1;
        int subcategoryBlockStart = 1;
        Set<String> subcategoryCurrencies = new TreeSet<>();
        Set<String> categoryCurrencies = new TreeSet<>();

        for (Map.Entry<CategoryKey, BigDecimal> e : sorted) {
            String category = e.getKey().category();
            String subcategory = e.getKey().subcategory();
            String currency = e.getKey().currency();

            boolean categoryChanged = currentCategory != null && !currentCategory.equals(category);
            boolean subcategoryChanged = currentSubcategory != null
                    && (categoryChanged || !currentSubcategory.equals(subcategory));

            if (subcategoryChanged) {
                flushSubcategoryFormulas(catRows, currentCategory, currentSubcategory, subcategoryBlockStart, catRows.size() - 1, subcategoryCurrencies);
                subcategoryCurrencies.clear();
            }
            if (categoryChanged) {
                flushCategoryFormulas(catRows, currentCategory, categoryBlockStart, catRows.size() - 1, categoryCurrencies);
                categoryCurrencies.clear();
            }
            if (subcategoryChanged || currentSubcategory == null) {
                subcategoryBlockStart = catRows.size();
            }
            if (categoryChanged || currentCategory == null) {
                categoryBlockStart = catRows.size();
            }

            currentCategory = category;
            currentSubcategory = subcategory;
            subcategoryCurrencies.add(currency);
            categoryCurrencies.add(currency);

            catRows.add(List.of(category, subcategory, currency, e.getKey().nickname(), e.getValue()));
        }
        if (currentCategory != null) {
            flushSubcategoryFormulas(catRows, currentCategory, currentSubcategory, subcategoryBlockStart, catRows.size() - 1, subcategoryCurrencies);
            flushCategoryFormulas(catRows, currentCategory, categoryBlockStart, catRows.size() - 1, categoryCurrencies);
        }

        return catRows;
    }

    /** Row indices below are 0-based positions in {@code catRows} (header at index 0); spreadsheet row = index + 1. */
    private static void flushSubcategoryFormulas(
            List<List<Object>> catRows,
            String category,
            String subcategory,
            int startRow,
            int endRow,
            Set<String> currencies
    ) {
        if (subcategory == null || subcategory.isBlank() || currencies.isEmpty()) {
            return;
        }
        for (String currency : currencies) {
            String range = "C" + (startRow + 1) + ":C" + (endRow + 1);
            String amountRange = "E" + (startRow + 1) + ":E" + (endRow + 1);
            String formula = "SUMIFS(" + amountRange + "," + range + ",\"" + escapeFormulaString(currency) + "\")";
            catRows.add(List.of(category, subcategory, currency, BY_SUBCATEGORY_SUBTOTAL, new FormulaCell(formula)));
        }
    }

    private static void flushCategoryFormulas(
            List<List<Object>> catRows,
            String category,
            int startRow,
            int endRow,
            Set<String> currencies
    ) {
        if (currencies.isEmpty()) {
            return;
        }
        for (String currency : currencies) {
            String currencyRange = "C" + (startRow + 1) + ":C" + (endRow + 1);
            String nicknameRange = "D" + (startRow + 1) + ":D" + (endRow + 1);
            String amountRange = "E" + (startRow + 1) + ":E" + (endRow + 1);
            String formula = "SUMIFS(" + amountRange
                    + "," + currencyRange + ",\"" + escapeFormulaString(currency) + "\""
                    + "," + nicknameRange + ",\"<>" + escapeFormulaString(BY_SUBCATEGORY_SUBTOTAL) + "\""
                    + "," + nicknameRange + ",\"<>" + escapeFormulaString(BY_CATEGORY_SUBTOTAL) + "\")";
            catRows.add(List.of(category, "", currency, BY_CATEGORY_SUBTOTAL, new FormulaCell(formula)));
        }
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

    record CategorySlot(String category, String subcategory, String currency) {
    }

    record CategoryKey(String category, String subcategory, String currency, String nickname) {
    }

    public record RowRange(int startRow, int endRowInclusive, int level) {
    }
}

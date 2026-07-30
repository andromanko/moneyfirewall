package com.moneyfirewall.service;

import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.reporting.ColoredCell;
import com.moneyfirewall.reporting.FormulaCell;
import com.moneyfirewall.reporting.ReportTables;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    public static final String SAVINGS_BLOCK_TITLE = "Накопления";
    private static final String FEE_CATEGORY_NAME = "Комиссии";
    private static final String FEE_NICKNAME = "FEE";
    /** Bounded row count used for cross-sheet SUMIFS ranges instead of whole-column references,
     * which POI's formula evaluator would otherwise try to materialize in full. */
    private static final int TX_DATA_LAST_ROW = 100_000;

    private static final String[] RUSSIAN_MONTHS = {
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    };

    // Fixed Transactions sheet column letters, referenced by Summary's formulas.
    private static final String TX_COL_DIRECTION = "B";
    private static final String TX_COL_AMOUNT_CONVERTED = "F";
    private static final String TX_COL_CATEGORY = "H";
    private static final String TX_COL_SUBCATEGORY = "I";
    private static final String TX_COL_NICKNAME = "J";
    private static final String TX_COL_MONTH = "L";

    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final MerchantAliasService merchantAliasService;
    private final BudgetService budgetService;
    private final NbrbExchangeRateService nbrbExchangeRateService;
    private final SavingsRateService savingsRateService;

    public ReportService(
            TransactionRepository transactionRepository,
            CategoryService categoryService,
            MerchantAliasService merchantAliasService,
            BudgetService budgetService,
            NbrbExchangeRateService nbrbExchangeRateService,
            SavingsRateService savingsRateService
    ) {
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.merchantAliasService = merchantAliasService;
        this.budgetService = budgetService;
        this.nbrbExchangeRateService = nbrbExchangeRateService;
        this.savingsRateService = savingsRateService;
    }

    @Transactional(readOnly = true)
    public ReportTables build(UUID budgetId, Instant from, Instant to) {
        List<Transaction> tx = transactionRepository.findAllInRange(budgetId, from, to);
        String defaultCurrency = budgetService.getDefaultCurrency(budgetId);

        // Per-currency maps (unaffected by the default-currency conversion below) feed the
        // unchanged, original-currency ByCategory sheet.
        Map<CategoryKey, BigDecimal> byCategoryAndCounterparty = new HashMap<>();
        Map<CategorySlot, BigDecimal> byCategoryTotal = new HashMap<>();
        Map<String, BigDecimal> byMember = new HashMap<>();

        // Converted-to-default-currency accumulators, used only to decide Summary's row order and
        // the Telegram preview totals; the sheet's own numbers come from live formulas.
        Map<CategoryPair, BigDecimal> categoryConvertedTotal = new HashMap<>();
        // Keyed by savings subcategory name ("" for a deposit booked straight on the root).
        Map<String, BigDecimal> savingsConvertedTotal = new TreeMap<>();
        BigDecimal incomeTotal = BigDecimal.ZERO;
        BigDecimal expenseTotal = BigDecimal.ZERO;
        Set<String> monthKeysSeen = new TreeSet<>();

        Map<UUID, BigDecimal> lastBalanceByAccount = new HashMap<>();

        List<List<Object>> txRows = new ArrayList<>();
        txRows.add(List.of("Дата и время", "Тип", "Сумма", "Валюта", "Курс НБРБ",
                "Сумма в " + defaultCurrency, "Счёт", "Категория", "Подкатегория", "Контрагент", "Участник", "Месяц", "ID"));

        for (Transaction t : tx) {
            boolean isTransfer = t.getDirection() == TransactionDirection.TRANSFER || t.getTransferGroup() != null;
            Category category = t.getCategory();
            CategoryColumns cols = categoryColumns(budgetId, category);
            String member = t.getUser() == null ? "" : t.getUser().getDisplayName();
            String account = t.getAccount() == null ? "" : t.getAccount().getName();
            String cp = merchantAliasService.resolveDisplayName(budgetId, t.getCounterpartyRaw(), t.getCounterpartyNormalized());
            String currency = t.getCurrency() == null ? "" : t.getCurrency();
            String monthKey = YearMonth.from(t.getOccurredAt().atZone(ZoneOffset.UTC)).toString();
            monthKeysSeen.add(monthKey);

            UUID accountId = t.getAccount() == null ? null : t.getAccount().getId();
            BigDecimal balanceBefore = accountId == null ? null : lastBalanceByAccount.get(accountId);
            int sheetRow = txRows.size() + 1;
            ConvertedCells converted = convert(t, currency, defaultCurrency, balanceBefore, sheetRow);
            if (accountId != null && t.getBalanceAfter() != null && defaultCurrency.equalsIgnoreCase(t.getBalanceCurrency())) {
                lastBalanceByAccount.put(accountId, t.getBalanceAfter());
            }

            if (!isTransfer) {
                if (t.getDirection() == TransactionDirection.INCOME) {
                    incomeTotal = incomeTotal.add(converted.numericAmount());
                } else if (t.getDirection() == TransactionDirection.EXPENSE) {
                    // Savings deposits are money set aside, not spent: they stay out of the
                    // expense total and the Summary category breakdown, and get their own block.
                    if (categoryService.isSavings(category)) {
                        savingsConvertedTotal.merge(cols.subcategory(), converted.numericAmount(), BigDecimal::add);
                    } else if (!categoryService.isCash(category)) {
                        expenseTotal = expenseTotal.add(converted.numericAmount());
                        categoryConvertedTotal.merge(new CategoryPair(cols.category(), cols.subcategory()), converted.numericAmount(), BigDecimal::add);

                        if (FEE_CATEGORY_NAME.equalsIgnoreCase(cols.category()) || FEE_NICKNAME.equalsIgnoreCase(cp)) {
                            // fees participate in the Summary formulas directly from Transactions; no Java accumulation needed.
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

            String directionLabel = isTransfer ? "Перевод"
                    : t.getDirection() == TransactionDirection.INCOME ? "Доход"
                    : t.getDirection() == TransactionDirection.EXPENSE ? "Расход"
                    : t.getDirection().name();

            txRows.add(List.of(
                    t.getOccurredAt().toString(),
                    directionLabel,
                    t.getAmount(),
                    t.getCurrency(),
                    converted.rateCell() == null ? "" : converted.rateCell(),
                    converted.amountCell(),
                    account,
                    cols.category(),
                    cols.subcategory(),
                    cp,
                    member,
                    monthKey,
                    t.getId().toString()
            ));
        }

        List<YearMonth> months = monthRange(from, to);
        List<List<Object>> summary = buildSummarySheet(
                months, categoryConvertedTotal, savingsRows(budgetId, savingsConvertedTotal, defaultCurrency), defaultCurrency);

        List<List<Object>> catRows = buildByCategorySheet(byCategoryAndCounterparty);

        List<List<Object>> memberRows = new ArrayList<>();
        memberRows.add(List.of("member", "expense"));
        byMember.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> memberRows.add(List.of(e.getKey(), e.getValue())));

        return new ReportTables(summary, catRows, memberRows, txRows, incomeTotal, expenseTotal, defaultCurrency);
    }

    /**
     * Pairs each savings subcategory's accumulated deposits with the currency it is held in, and
     * restates the total in that currency at today's rate. Subcategories that exist but saw no
     * deposits in the period are still listed (with a zero total) so the block mirrors the
     * configured savings structure rather than only what happened to move this month.
     */
    private List<SavingsRow> savingsRows(UUID budgetId, Map<String, BigDecimal> savingsConvertedTotal, String defaultCurrency) {
        Map<String, String> currencyBySubcategory = new LinkedHashMap<>();
        for (Category c : categoryService.listSavingsSubcategories(budgetId)) {
            currencyBySubcategory.put(c.getName(), c.getCurrency());
        }
        // Include any subcategory that only shows up in the data (e.g. renamed/deleted since).
        for (String subcategory : savingsConvertedTotal.keySet()) {
            if (!subcategory.isBlank()) {
                currencyBySubcategory.putIfAbsent(subcategory, null);
            }
        }

        List<SavingsRow> rows = new ArrayList<>();
        currencyBySubcategory.forEach((subcategory, currency) -> {
            BigDecimal total = savingsConvertedTotal.getOrDefault(subcategory, BigDecimal.ZERO);
            rows.add(new SavingsRow(subcategory, currency, total, amountInAsset(total, currency, defaultCurrency)));
        });
        BigDecimal rootTotal = savingsConvertedTotal.get("");
        if (rootTotal != null && rootTotal.signum() != 0) {
            rows.add(new SavingsRow("", null, rootTotal, null));
        }
        return rows;
    }

    private BigDecimal amountInAsset(BigDecimal totalInDefault, String currency, String defaultCurrency) {
        if (currency == null || currency.isBlank() || totalInDefault.signum() == 0) {
            return null;
        }
        return savingsRateService.convertToAsset(totalInDefault, currency, defaultCurrency).orElse(null);
    }

    private record ConvertedCells(Object rateCell, Object amountCell, BigDecimal numericAmount) {
    }

    /**
     * Converts one transaction's amount into the budget's default currency. Trivial when the
     * transaction is already in that currency; otherwise prefers an exact account-balance
     * before/after difference (green) over an NBRB rate lookup (yellow) when both the current and
     * the preceding same-account balance are known in the default currency.
     */
    private ConvertedCells convert(Transaction t, String currency, String defaultCurrency, BigDecimal balanceBeforeInDefault, int sheetRow) {
        if (currency.isBlank() || currency.equalsIgnoreCase(defaultCurrency)) {
            return new ConvertedCells(null, t.getAmount(), t.getAmount());
        }
        boolean balanceUsable = t.getBalanceAfter() != null
                && t.getBalanceCurrency() != null
                && defaultCurrency.equalsIgnoreCase(t.getBalanceCurrency())
                && balanceBeforeInDefault != null;
        if (balanceUsable) {
            BigDecimal diff = t.getBalanceAfter().subtract(balanceBeforeInDefault).abs();
            return new ConvertedCells(null, new ColoredCell(diff, ColoredCell.Color.GREEN), diff);
        }
        LocalDate date = t.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        try {
            BigDecimal rate = nbrbExchangeRateService.rate(currency, defaultCurrency, date);
            BigDecimal amount = t.getAmount().multiply(rate);
            Object rateCell = new ColoredCell(rate, ColoredCell.Color.YELLOW);
            Object amountCell = new ColoredCell(new FormulaCell("C" + sheetRow + "*E" + sheetRow), ColoredCell.Color.YELLOW);
            return new ConvertedCells(rateCell, amountCell, amount);
        } catch (Exception e) {
            // NBRB unreachable/no rate found: don't fail the whole report, fall back to the raw
            // amount (uncolored) and flag the missing rate in the rate column.
            return new ConvertedCells("н/д", t.getAmount(), t.getAmount());
        }
    }

    private List<YearMonth> monthRange(Instant from, Instant to) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth start = YearMonth.from(from.atZone(ZoneOffset.UTC));
        Instant lastInclusive = to.isAfter(from) ? to.minusSeconds(1) : from;
        YearMonth end = YearMonth.from(lastInclusive.atZone(ZoneOffset.UTC));
        YearMonth cursor = start;
        while (!cursor.isAfter(end)) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        if (months.isEmpty()) {
            months.add(start);
        }
        return months;
    }

    private static String monthKey(YearMonth ym) {
        return ym.toString();
    }

    private static String monthHeader(YearMonth ym) {
        return RUSSIAN_MONTHS[ym.getMonthValue() - 1] + " " + ym.getYear();
    }

    private static String columnLetter(int index0Based) {
        StringBuilder sb = new StringBuilder();
        int n = index0Based;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    static List<List<Object>> buildSummarySheet(List<YearMonth> months, Map<CategoryPair, BigDecimal> categoryConvertedTotal) {
        return buildSummarySheet(months, categoryConvertedTotal, List.of(), "");
    }

    static List<List<Object>> buildSummarySheet(
            List<YearMonth> months,
            Map<CategoryPair, BigDecimal> categoryConvertedTotal,
            List<SavingsRow> savingsRows,
            String defaultCurrency
    ) {
        List<List<Object>> summary = new ArrayList<>();
        int firstMonthCol = 2; // column C (0-based index 2)
        int lastMonthCol = firstMonthCol + months.size() - 1;
        int totalCol = lastMonthCol + 1;

        List<Object> header = new ArrayList<>(List.of("Показатель", ""));
        for (YearMonth ym : months) {
            header.add(monthHeader(ym));
        }
        header.add("Итого");
        summary.add(header);

        int incomeRow = summary.size() + 1;
        summary.add(metricRow("Доход", incomeRow, months, ReportService::incomeFormula, firstMonthCol, lastMonthCol));
        int expenseRow = summary.size() + 1;
        summary.add(metricRow("Расход", expenseRow, months, ReportService::expenseFormula, firstMonthCol, lastMonthCol));
        summary.add(netRow(incomeRow, expenseRow, months, firstMonthCol, totalCol));
        int feesRow = summary.size() + 1;
        summary.add(metricRow("Комиссии", feesRow, months, ReportService::feesFormula, firstMonthCol, lastMonthCol));

        summary.add(List.of());
        List<Object> catHeader = new ArrayList<>(List.of("Категория", "Подкатегория"));
        for (YearMonth ym : months) {
            catHeader.add(monthHeader(ym));
        }
        catHeader.add("Итого");
        summary.add(catHeader);

        Map<String, BigDecimal> categoryTotals = new HashMap<>();
        Map<String, List<Map.Entry<CategoryPair, BigDecimal>>> bySubcategory = new HashMap<>();
        for (Map.Entry<CategoryPair, BigDecimal> e : categoryConvertedTotal.entrySet()) {
            String category = e.getKey().category();
            categoryTotals.merge(category, e.getValue(), BigDecimal::add);
            bySubcategory.computeIfAbsent(category, k -> new ArrayList<>()).add(e);
        }

        List<String> categoriesSorted = categoryTotals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();

        for (String category : categoriesSorted) {
            List<Map.Entry<CategoryPair, BigDecimal>> entries = bySubcategory.get(category);
            entries.sort(Map.Entry.<CategoryPair, BigDecimal>comparingByValue(Comparator.reverseOrder())
                    .thenComparing(e -> e.getKey().subcategory()));
            for (Map.Entry<CategoryPair, BigDecimal> e : entries) {
                List<Object> row = new ArrayList<>(List.of(e.getKey().category(), e.getKey().subcategory()));
                for (YearMonth ym : months) {
                    row.add(categoryExpenseFormula(e.getKey().category(), e.getKey().subcategory(), ym));
                }
                row.add(totalFormula(summary.size() + 1, firstMonthCol, lastMonthCol));
                summary.add(row);
            }
            if (entries.size() > 1) {
                // Parent rollup: sums every subcategory row plus any transaction categorized
                // directly on the parent (blank subcategory), via a category-only SUMIFS.
                List<Object> row = new ArrayList<>(List.of(category, BY_CATEGORY_SUBTOTAL));
                for (YearMonth ym : months) {
                    row.add(categoryTotalFormula(category, ym));
                }
                row.add(totalFormula(summary.size() + 1, firstMonthCol, lastMonthCol));
                summary.add(row);
            }
        }

        appendSavingsBlock(summary, months, savingsRows, defaultCurrency, firstMonthCol, lastMonthCol);
        return summary;
    }

    /**
     * Savings get their own block rather than a line in the category breakdown: each subcategory
     * shows its per-month/total deposits in the report currency (live SUMIFS, same as every other
     * figure) plus a static snapshot of what that total is worth in the subcategory's own
     * currency/crypto at today's rate.
     */
    private static void appendSavingsBlock(
            List<List<Object>> summary,
            List<YearMonth> months,
            List<SavingsRow> savingsRows,
            String defaultCurrency,
            int firstMonthCol,
            int lastMonthCol
    ) {
        if (savingsRows.isEmpty()) {
            return;
        }
        summary.add(List.of());
        List<Object> header = new ArrayList<>(List.of(SAVINGS_BLOCK_TITLE, "Валюта"));
        for (YearMonth ym : months) {
            header.add(monthHeader(ym));
        }
        header.add("Итого " + defaultCurrency);
        header.add("В валюте накоплений");
        summary.add(header);

        for (SavingsRow r : savingsRows) {
            String label = r.subcategory() == null || r.subcategory().isBlank() ? "(без подкатегории)" : r.subcategory();
            List<Object> row = new ArrayList<>();
            row.add(label);
            row.add(r.currency() == null ? "" : r.currency());
            for (YearMonth ym : months) {
                row.add(savingsExpenseFormula(r.subcategory(), ym));
            }
            row.add(totalFormula(summary.size() + 1, firstMonthCol, lastMonthCol));
            // Rendered as text, not a number: crypto amounts need far more decimals than the
            // sheet-wide money format ("# ##0.00") would keep, which would show BTC as 0.00.
            row.add(formatAssetAmount(r.amountInOwnCurrency()));
            summary.add(row);
        }

        List<Object> totalRow = new ArrayList<>(List.of(SAVINGS_BLOCK_TITLE, BY_CATEGORY_SUBTOTAL));
        for (YearMonth ym : months) {
            totalRow.add(categoryTotalFormula(CategoryService.SAVINGS, ym));
        }
        totalRow.add(totalFormula(summary.size() + 1, firstMonthCol, lastMonthCol));
        summary.add(totalRow);
    }

    private static String formatAssetAmount(BigDecimal amount) {
        if (amount == null) {
            return "н/д";
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() < 2) {
            stripped = stripped.setScale(2, RoundingMode.HALF_UP);
        }
        return stripped.toPlainString();
    }

    private static FormulaCell savingsExpenseFormula(String subcategory, YearMonth ym) {
        return categoryExpenseFormula(CategoryService.SAVINGS, subcategory == null ? "" : subcategory, ym);
    }

    /**
     * @param amountInOwnCurrency null when no rate could be resolved for {@code currency}.
     */
    public record SavingsRow(String subcategory, String currency, BigDecimal totalInDefault, BigDecimal amountInOwnCurrency) {
    }

    private static List<Object> metricRow(String label, int rowNumber, List<YearMonth> months, java.util.function.Function<YearMonth, FormulaCell> formulaFn, int firstMonthCol, int lastMonthCol) {
        List<Object> row = new ArrayList<>(List.of(label, ""));
        for (YearMonth ym : months) {
            row.add(formulaFn.apply(ym));
        }
        row.add(totalFormula(rowNumber, firstMonthCol, lastMonthCol));
        return row;
    }

    private static List<Object> netRow(int incomeRow, int expenseRow, List<YearMonth> months, int firstMonthCol, int totalCol) {
        List<Object> row = new ArrayList<>(List.of("Нетто", ""));
        for (int i = 0; i < months.size(); i++) {
            String col = columnLetter(firstMonthCol + i);
            row.add(new FormulaCell(col + incomeRow + "-" + col + expenseRow));
        }
        String totalColLetter = columnLetter(totalCol);
        row.add(new FormulaCell(totalColLetter + incomeRow + "-" + totalColLetter + expenseRow));
        return row;
    }

    private static FormulaCell totalFormula(int rowNumber, int firstMonthCol, int lastMonthCol) {
        return new FormulaCell("SUM(" + columnLetter(firstMonthCol) + rowNumber + ":" + columnLetter(lastMonthCol) + rowNumber + ")");
    }

    private static String txRange(String column) {
        return "Transactions!" + column + "2:" + column + TX_DATA_LAST_ROW;
    }

    private static String escapeFormulaString(String s) {
        return s == null ? "" : s.replace("\"", "\"\"");
    }

    private static FormulaCell incomeFormula(YearMonth ym) {
        return new FormulaCell("SUMIFS(" + txRange(TX_COL_AMOUNT_CONVERTED) + "," + txRange(TX_COL_DIRECTION) + ",\"Доход\","
                + txRange(TX_COL_MONTH) + ",\"" + monthKey(ym) + "\")");
    }

    private static FormulaCell expenseFormula(YearMonth ym) {
        return new FormulaCell("SUMIFS(" + txRange(TX_COL_AMOUNT_CONVERTED) + "," + txRange(TX_COL_DIRECTION) + ",\"Расход\","
                + txRange(TX_COL_MONTH) + ",\"" + monthKey(ym) + "\"," + txRange(TX_COL_CATEGORY) + ",\"<>CASH\","
                + txRange(TX_COL_CATEGORY) + ",\"<>" + escapeFormulaString(CategoryService.SAVINGS) + "\")");
    }

    private static FormulaCell feesFormula(YearMonth ym) {
        String byCategory = "SUMIFS(" + txRange(TX_COL_AMOUNT_CONVERTED) + "," + txRange(TX_COL_DIRECTION) + ",\"Расход\","
                + txRange(TX_COL_MONTH) + ",\"" + monthKey(ym) + "\"," + txRange(TX_COL_CATEGORY) + ",\"" + escapeFormulaString(FEE_CATEGORY_NAME) + "\")";
        String byNickname = "SUMIFS(" + txRange(TX_COL_AMOUNT_CONVERTED) + "," + txRange(TX_COL_DIRECTION) + ",\"Расход\","
                + txRange(TX_COL_MONTH) + ",\"" + monthKey(ym) + "\"," + txRange(TX_COL_CATEGORY) + ",\"<>" + escapeFormulaString(FEE_CATEGORY_NAME) + "\","
                + txRange(TX_COL_NICKNAME) + ",\"" + FEE_NICKNAME + "\")";
        return new FormulaCell(byCategory + "+" + byNickname);
    }

    private static FormulaCell categoryExpenseFormula(String category, String subcategory, YearMonth ym) {
        return new FormulaCell("SUMIFS(" + txRange(TX_COL_AMOUNT_CONVERTED) + "," + txRange(TX_COL_CATEGORY) + ",\"" + escapeFormulaString(category)
                + "\"," + txRange(TX_COL_SUBCATEGORY) + ",\"" + escapeFormulaString(subcategory) + "\"," + txRange(TX_COL_DIRECTION) + ",\"Расход\","
                + txRange(TX_COL_MONTH) + ",\"" + monthKey(ym) + "\")");
    }

    /** Category-only SUMIFS (no subcategory filter) — rolls up every subcategory plus any
     * transaction categorized directly on the parent, with no risk of double-counting since a
     * transaction's category/subcategory pair is unique per row. */
    private static FormulaCell categoryTotalFormula(String category, YearMonth ym) {
        return new FormulaCell("SUMIFS(" + txRange(TX_COL_AMOUNT_CONVERTED) + "," + txRange(TX_COL_CATEGORY) + ",\"" + escapeFormulaString(category)
                + "\"," + txRange(TX_COL_DIRECTION) + ",\"Расход\","
                + txRange(TX_COL_MONTH) + ",\"" + monthKey(ym) + "\")");
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

    /**
     * Computes row ranges (0-based, inclusive) for Summary's category-breakdown block so each
     * category's subcategory rows can be collapsed under its "ИТОГО" rollup row, mirroring
     * ByCategory's outline. Categories with a single row (no rollup) have nothing to collapse.
     */
    public static List<RowRange> computeSummaryCategoryOutline(List<List<Object>> summaryRows) {
        List<RowRange> ranges = new ArrayList<>();
        int headerIdx = -1;
        for (int r = 0; r < summaryRows.size(); r++) {
            List<Object> row = summaryRows.get(r);
            if (!row.isEmpty() && "Категория".equals(row.getFirst())) {
                headerIdx = r;
                break;
            }
        }
        if (headerIdx < 0) {
            return ranges;
        }
        int blockStart = headerIdx + 1;
        for (int r = blockStart; r < summaryRows.size(); r++) {
            List<Object> row = summaryRows.get(r);
            Object subcategory = row.size() > 1 ? row.get(1) : null;
            // The savings block that follows has its own header and its own ИТОГО row; grouping
            // across it would fold that header away too.
            if (!row.isEmpty() && SAVINGS_BLOCK_TITLE.equals(row.getFirst()) && "Валюта".equals(String.valueOf(subcategory))) {
                break;
            }
            if (BY_CATEGORY_SUBTOTAL.equals(String.valueOf(subcategory))) {
                if (blockStart <= r - 1) {
                    ranges.add(new RowRange(blockStart, r - 1, 1));
                }
                blockStart = r + 1;
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

    record CategoryPair(String category, String subcategory) {
    }

    public record RowRange(int startRow, int endRowInclusive, int level) {
    }
}

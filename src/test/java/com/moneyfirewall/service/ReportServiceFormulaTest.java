package com.moneyfirewall.service;

import com.moneyfirewall.reporting.ColoredCell;
import com.moneyfirewall.reporting.ExcelReportExporter;
import com.moneyfirewall.reporting.FormulaCell;
import com.moneyfirewall.reporting.ReportTables;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check of the month-pivoted, single-currency Summary sheet: builds a Transactions
 * sheet by hand (mirroring what ReportService.build() would emit, including yellow/green
 * currency-converted cells), runs it through ExcelReportExporter, reopens the saved bytes, and
 * reads the cached formula results POI evaluated before writing.
 */
class ReportServiceFormulaTest {
    @Test
    void monthlySummaryFormulasEvaluateToCorrectTotals() throws Exception {
        List<List<Object>> transactions = new ArrayList<>();
        transactions.add(List.of("Дата и время", "Тип", "Сумма", "Валюта", "Курс НБРБ", "Сумма в BYN",
                "Счёт", "Категория", "Подкатегория", "Контрагент", "Участник", "Месяц"));

        // January: regular BYN expense/income, a CASH expense (excluded), a yellow (rate) foreign
        // expense, a green (balance-diff) foreign expense, two fee variants, and a transfer.
        transactions.add(txRow("2026-01-01T00:00:00Z", "Расход", "100", "BYN", "", new BigDecimal("100"),
                "Alfa", "Продукты", "Магазины", "Евроопт", "Andrey", "2026-01"));
        transactions.add(txRow("2026-01-02T00:00:00Z", "Доход", "1000", "BYN", "", new BigDecimal("1000"),
                "Alfa", "", "", "Salary", "Andrey", "2026-01"));
        transactions.add(txRow("2026-01-03T00:00:00Z", "Расход", "20", "BYN", "", new BigDecimal("20"),
                "Cash", "CASH", "", "", "Andrey", "2026-01"));
        transactions.add(txRow("2026-01-04T00:00:00Z", "Расход", "10", "EUR",
                new ColoredCell(new BigDecimal("3.5"), ColoredCell.Color.YELLOW),
                new ColoredCell(new FormulaCell("C5*E5"), ColoredCell.Color.YELLOW),
                "Alfa", "Продукты", "Магазины", "Евроопт", "Andrey", "2026-01"));
        transactions.add(txRow("2026-01-05T00:00:00Z", "Расход", "18", "USD", "",
                new ColoredCell(new BigDecimal("50"), ColoredCell.Color.GREEN),
                "Alfa", "Транспорт", "", "Такси", "Andrey", "2026-01"));
        transactions.add(txRow("2026-01-06T00:00:00Z", "Расход", "30", "BYN", "", new BigDecimal("30"),
                "Alfa", "Комиссии", "", "Bank fee", "Andrey", "2026-01"));
        transactions.add(txRow("2026-01-07T00:00:00Z", "Расход", "15", "BYN", "", new BigDecimal("15"),
                "Alfa", "Транспорт", "", "FEE", "Andrey", "2026-01"));
        transactions.add(txRow("2026-01-08T00:00:00Z", "Перевод", "999", "BYN", "", new BigDecimal("999"),
                "Alfa", "", "", "", "Andrey", "2026-01"));
        // A refund for the "Продукты/Магазины" expense above, entered as income against that same
        // category via the "💸 Расходные (компенсация)" flow — must net against the category total.
        transactions.add(txRow("2026-01-09T00:00:00Z", "Доход", "40", "BYN", "", new BigDecimal("40"),
                "Alfa", "Продукты", "Магазины", "Возврат", "Andrey", "2026-01"));

        // February: one expense, one income, to prove months don't bleed into each other.
        transactions.add(txRow("2026-02-01T00:00:00Z", "Расход", "200", "BYN", "", new BigDecimal("200"),
                "Alfa", "Продукты", "Магазины", "Евроопт", "Andrey", "2026-02"));
        transactions.add(txRow("2026-02-02T00:00:00Z", "Доход", "500", "BYN", "", new BigDecimal("500"),
                "Alfa", "", "", "Salary", "Andrey", "2026-02"));

        List<YearMonth> months = List.of(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        Map<ReportService.CategoryPair, BigDecimal> categoryConvertedTotal = new LinkedHashMap<>();
        categoryConvertedTotal.put(new ReportService.CategoryPair("Продукты", "Магазины"), new BigDecimal("335"));
        categoryConvertedTotal.put(new ReportService.CategoryPair("Транспорт", ""), new BigDecimal("65"));
        categoryConvertedTotal.put(new ReportService.CategoryPair("Комиссии", ""), new BigDecimal("30"));

        List<List<Object>> summary = ReportService.buildSummarySheet(months, categoryConvertedTotal);
        List<List<Object>> byCategory = List.of(List.of("category", "subcategory", "currency", "nickname", "amount"));
        List<List<Object>> byMember = List.of(List.of("member", "expense"));

        List<List<Object>> byHashtag = List.of(List.of("Хэштег"));
        ReportTables tables = new ReportTables(summary, byCategory, byMember, transactions, byHashtag,
                new BigDecimal("1500"), new BigDecimal("430"), "BYN");
        byte[] xlsx = new ExcelReportExporter().export(tables);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Map<String, Double> metrics = readMetrics(wb.getSheet("Summary"));
            assertEquals(1040.0, metrics.get("Доход|2026-01"), "1000 salary + 40 refund compensation");
            assertEquals(230.0, metrics.get("Расход|2026-01"), "100 + 35(yellow) + 50(green) + 30 + 15, CASH excluded");
            assertEquals(810.0, metrics.get("Нетто|2026-01"));
            assertEquals(45.0, metrics.get("Комиссии|2026-01"), "30 by category + 15 by nickname");
            assertEquals(500.0, metrics.get("Доход|2026-02"));
            assertEquals(200.0, metrics.get("Расход|2026-02"));
            assertEquals(300.0, metrics.get("Нетто|2026-02"));
            assertEquals(0.0, metrics.getOrDefault("Комиссии|2026-02", 0.0));
            assertEquals(1540.0, metrics.get("Доход|Итого"));
            assertEquals(430.0, metrics.get("Расход|Итого"));
            assertEquals(1110.0, metrics.get("Нетто|Итого"));
            assertEquals(45.0, metrics.get("Комиссии|Итого"));

            Map<String, Double> byCat = readCategoryBreakdown(wb.getSheet("Summary"));
            assertEquals(95.0, byCat.get("Продукты|Магазины|2026-01"), "100 BYN + 35 yellow-converted EUR - 40 refund compensation");
            assertEquals(200.0, byCat.get("Продукты|Магазины|2026-02"));
            assertEquals(295.0, byCat.get("Продукты|Магазины|Итого"));
            assertEquals(65.0, byCat.get("Транспорт||2026-01"), "50 green-converted USD taxi + 15 FEE-nickname row, both categorized Транспорт");
            assertEquals(30.0, byCat.get("Комиссии||2026-01"));
        }
    }

    private List<Object> txRow(String occurredAt, String direction, String amount, String currency,
            Object rateCell, Object amountCell, String account, String category, String subcategory,
            String nickname, String member, String monthKey) {
        java.util.List<Object> row = new ArrayList<>();
        row.add(occurredAt);
        row.add(direction);
        row.add(new BigDecimal(amount));
        row.add(currency);
        row.add(rateCell);
        row.add(amountCell);
        row.add(account);
        row.add(category);
        row.add(subcategory);
        row.add(nickname);
        row.add(member);
        row.add(monthKey);
        return row;
    }

    private Map<String, Double> readMetrics(XSSFSheet sheet) {
        Map<String, Double> result = new HashMap<>();
        List<String> monthCols = null;
        for (Row row : sheet) {
            if (row.getRowNum() == 0) {
                monthCols = new ArrayList<>();
                for (Cell c : row) {
                    if (c.getColumnIndex() < 2) {
                        continue;
                    }
                    monthCols.add(c.getStringCellValue());
                }
                continue;
            }
            Cell labelCell = row.getCell(0);
            if (labelCell == null || labelCell.getCellType() != CellType.STRING) {
                continue;
            }
            String label = labelCell.getStringCellValue();
            if ("Категория".equals(label)) {
                // Metrics block ends here; the category-breakdown block below can reuse
                // the same label (e.g. "Комиссии" is both a metric and a category name).
                break;
            }
            if (!List.of("Доход", "Расход", "Нетто", "Комиссии").contains(label)) {
                continue;
            }
            for (int i = 0; i < monthCols.size(); i++) {
                Cell valueCell = row.getCell(2 + i);
                if (valueCell == null) {
                    continue;
                }
                result.put(label + "|" + headerToKey(monthCols.get(i)), valueCell.getNumericCellValue());
            }
        }
        return result;
    }

    private String headerToKey(String header) {
        if ("Итого".equals(header)) {
            return "Итого";
        }
        Map<String, Integer> ru = Map.of("Январь", 1, "Февраль", 2);
        String[] parts = header.split(" ");
        return parts[1] + "-" + String.format("%02d", ru.getOrDefault(parts[0], 0));
    }

    private Map<String, Double> readCategoryBreakdown(XSSFSheet sheet) {
        Map<String, Double> result = new HashMap<>();
        boolean inBlock = false;
        List<String> monthCols = null;
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == CellType.STRING && "Категория".equals(first.getStringCellValue())) {
                inBlock = true;
                monthCols = new ArrayList<>();
                for (Cell c : row) {
                    if (c.getColumnIndex() < 2) {
                        continue;
                    }
                    monthCols.add(c.getStringCellValue());
                }
                continue;
            }
            if (!inBlock || first == null || first.getCellType() != CellType.STRING || first.getStringCellValue().isBlank()) {
                continue;
            }
            String category = first.getStringCellValue();
            Cell subCell = row.getCell(1);
            String subcategory = subCell != null && subCell.getCellType() == CellType.STRING ? subCell.getStringCellValue() : "";
            for (int i = 0; i < monthCols.size(); i++) {
                Cell valueCell = row.getCell(2 + i);
                if (valueCell == null) {
                    continue;
                }
                result.put(category + "|" + subcategory + "|" + headerToKey(monthCols.get(i)), valueCell.getNumericCellValue());
            }
        }
        return result;
    }
}

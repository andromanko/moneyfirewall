package com.moneyfirewall.service;

import com.moneyfirewall.reporting.ExcelReportExporter;
import com.moneyfirewall.reporting.ReportTables;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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
 * End-to-end check that Summary/ByCategory are now live formulas computed from the Transactions
 * sheet (SUMIFS/SUMPRODUCT), not literal Java-computed numbers. Builds a small workbook with
 * ExcelReportExporter, reopens the saved bytes, and reads the cached formula results POI evaluated
 * before writing — this exercises the exact artifact a user would download, not just formula text.
 */
class ReportServiceFormulaTest {
    @Test
    void summaryAndByCategoryFormulasEvaluateToCorrectTotals() throws Exception {
        List<List<Object>> transactions = new ArrayList<>();
        transactions.add(List.of("occurredAt", "direction", "amount", "currency", "account", "category", "subcategory", "nickname", "member", "isTransfer"));
        transactions.add(txRow("2026-01-01T00:00:00Z", "EXPENSE", "100", "BYN", "Alfa", "Продукты", "Магазины", "Евроопт", "Andrey", false));
        transactions.add(txRow("2026-01-02T00:00:00Z", "EXPENSE", "50", "BYN", "Alfa", "Продукты", "Магазины", "Санта", "Andrey", false));
        transactions.add(txRow("2026-01-03T00:00:00Z", "INCOME", "1000", "BYN", "Alfa", "", "", "Salary", "Andrey", false));
        transactions.add(txRow("2026-01-04T00:00:00Z", "EXPENSE", "20", "BYN", "Cash", "CASH", "", "", "Andrey", false));
        transactions.add(txRow("2026-01-05T00:00:00Z", "EXPENSE", "200", "BYN", "Alfa", "Транспорт", "", "Такси", "Andrey", false));
        transactions.add(txRow("2026-01-06T00:00:00Z", "EXPENSE", "30", "BYN", "Alfa", "Комиссии", "", "Bank fee", "Andrey", false));
        transactions.add(txRow("2026-01-07T00:00:00Z", "EXPENSE", "5", "EUR", "Alfa", "Продукты", "Магазины", "Евроопт", "Andrey", false));
        transactions.add(txRow("2026-01-08T00:00:00Z", "EXPENSE", "999", "BYN", "Alfa", "Продукты", "Магазины", "Евроопт", "Andrey", true));
        transactions.add(txRow("2026-01-09T00:00:00Z", "EXPENSE", "15", "BYN", "Alfa", "Транспорт", "", "FEE", "Andrey", false));

        Map<String, BigDecimal> incomeByCurrency = new LinkedHashMap<>(Map.of("BYN", new BigDecimal("1000")));
        Map<String, BigDecimal> expenseByCurrency = new LinkedHashMap<>(Map.of(
                "BYN", new BigDecimal("395"),
                "EUR", new BigDecimal("5")
        ));
        Map<String, BigDecimal> feesByCurrency = new LinkedHashMap<>(Map.of("BYN", new BigDecimal("45")));

        Map<ReportService.CategorySlot, BigDecimal> byCategoryTotal = new LinkedHashMap<>();
        byCategoryTotal.put(new ReportService.CategorySlot("Продукты", "Магазины", "BYN"), new BigDecimal("150"));
        byCategoryTotal.put(new ReportService.CategorySlot("Продукты", "Магазины", "EUR"), new BigDecimal("5"));
        byCategoryTotal.put(new ReportService.CategorySlot("Транспорт", "", "BYN"), new BigDecimal("215"));
        byCategoryTotal.put(new ReportService.CategorySlot("Комиссии", "", "BYN"), new BigDecimal("30"));

        Map<ReportService.CategoryKey, BigDecimal> byCategoryAndCounterparty = new LinkedHashMap<>();
        byCategoryAndCounterparty.put(new ReportService.CategoryKey("Продукты", "Магазины", "BYN", "Евроопт"), new BigDecimal("100"));
        byCategoryAndCounterparty.put(new ReportService.CategoryKey("Продукты", "Магазины", "BYN", "Санта"), new BigDecimal("50"));
        byCategoryAndCounterparty.put(new ReportService.CategoryKey("Продукты", "Магазины", "EUR", "Евроопт"), new BigDecimal("5"));
        byCategoryAndCounterparty.put(new ReportService.CategoryKey("Транспорт", "", "BYN", "Такси"), new BigDecimal("200"));
        byCategoryAndCounterparty.put(new ReportService.CategoryKey("Транспорт", "", "BYN", "FEE"), new BigDecimal("15"));
        byCategoryAndCounterparty.put(new ReportService.CategoryKey("Комиссии", "", "BYN", "Bank fee"), new BigDecimal("30"));

        List<List<Object>> summary = ReportService.buildSummarySheet(incomeByCurrency, expenseByCurrency, feesByCurrency, byCategoryTotal);
        List<List<Object>> byCategory = ReportService.buildByCategorySheet(byCategoryAndCounterparty);
        List<List<Object>> byMember = List.of(List.of("member", "expense"));

        ReportTables tables = new ReportTables(summary, byCategory, byMember, transactions, incomeByCurrency, expenseByCurrency);
        byte[] xlsx = new ExcelReportExporter().export(tables);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Map<String, Double> summaryValues = readSummaryMetrics(wb.getSheet("Summary"));
            assertEquals(1000.0, summaryValues.get("income|BYN"));
            assertEquals(395.0, summaryValues.get("expense|BYN"));
            assertEquals(605.0, summaryValues.get("net|BYN"));
            assertEquals(45.0, summaryValues.get("fees|BYN"));
            assertEquals(0.0, summaryValues.getOrDefault("income|EUR", 0.0));
            assertEquals(5.0, summaryValues.get("expense|EUR"));
            assertEquals(-5.0, summaryValues.get("net|EUR"));

            Map<String, Double> categoryBreakdown = readSummaryCategoryBreakdown(wb.getSheet("Summary"));
            assertEquals(150.0, categoryBreakdown.get("Продукты|Магазины|BYN"));
            assertEquals(5.0, categoryBreakdown.get("Продукты|Магазины|EUR"));
            assertEquals(215.0, categoryBreakdown.get("Транспорт||BYN"));

            Map<String, Double> subtotals = readByCategorySubtotals(wb.getSheet("ByCategory"));
            assertEquals(150.0, subtotals.get("Подытог|Продукты|Магазины|BYN"), "subcategory subtotal");
            assertEquals(150.0, subtotals.get("ИТОГО|Продукты||BYN"), "category subtotal must not double-count the subcategory subtotal row within its range");
            assertEquals(5.0, subtotals.get("ИТОГО|Продукты||EUR"));
            assertEquals(215.0, subtotals.get("ИТОГО|Транспорт||BYN"), "no subcategory subtotal row for a blank subcategory, but category subtotal still works");
            assertEquals(30.0, subtotals.get("ИТОГО|Комиссии||BYN"));
        }
    }

    private List<Object> txRow(String occurredAt, String direction, String amount, String currency,
            String account, String category, String subcategory, String nickname, String member, boolean isTransfer) {
        return List.of(occurredAt, direction, new BigDecimal(amount), currency, account, category, subcategory, nickname, member, isTransfer);
    }

    private Map<String, Double> readSummaryMetrics(XSSFSheet sheet) {
        Map<String, Double> result = new HashMap<>();
        for (Row row : sheet) {
            if (row.getRowNum() == 0) {
                continue;
            }
            Cell metricCell = row.getCell(0);
            Cell currencyCell = row.getCell(1);
            Cell valueCell = row.getCell(2);
            if (metricCell == null || currencyCell == null || valueCell == null || metricCell.getCellType() != CellType.STRING) {
                continue;
            }
            String metric = metricCell.getStringCellValue();
            if (!List.of("income", "expense", "net", "fees").contains(metric)) {
                continue;
            }
            result.put(metric + "|" + currencyCell.getStringCellValue(), valueCell.getNumericCellValue());
        }
        return result;
    }

    private Map<String, Double> readSummaryCategoryBreakdown(XSSFSheet sheet) {
        Map<String, Double> result = new HashMap<>();
        boolean inBreakdown = false;
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == CellType.STRING && "category".equals(first.getStringCellValue())) {
                inBreakdown = true;
                continue;
            }
            if (!inBreakdown) {
                continue;
            }
            Cell catCell = row.getCell(0);
            Cell subCell = row.getCell(1);
            Cell curCell = row.getCell(2);
            Cell valCell = row.getCell(3);
            if (catCell == null || valCell == null) {
                continue;
            }
            String key = catCell.getStringCellValue() + "|" + safeString(subCell) + "|" + safeString(curCell);
            result.put(key, valCell.getNumericCellValue());
        }
        return result;
    }

    private Map<String, Double> readByCategorySubtotals(XSSFSheet sheet) {
        Map<String, Double> result = new HashMap<>();
        for (Row row : sheet) {
            if (row.getRowNum() == 0) {
                continue;
            }
            Cell catCell = row.getCell(0);
            Cell subCell = row.getCell(1);
            Cell curCell = row.getCell(2);
            Cell nickCell = row.getCell(3);
            Cell amountCell = row.getCell(4);
            if (catCell == null || nickCell == null || amountCell == null || nickCell.getCellType() != CellType.STRING) {
                continue;
            }
            String nickname = nickCell.getStringCellValue();
            if (!ReportService.BY_SUBCATEGORY_SUBTOTAL.equals(nickname) && !ReportService.BY_CATEGORY_SUBTOTAL.equals(nickname)) {
                continue;
            }
            String key = nickname + "|" + catCell.getStringCellValue() + "|" + safeString(subCell) + "|" + safeString(curCell);
            result.put(key, amountCell.getNumericCellValue());
        }
        return result;
    }

    private String safeString(Cell cell) {
        return cell == null ? "" : cell.getStringCellValue();
    }
}

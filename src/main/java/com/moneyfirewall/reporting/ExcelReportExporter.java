package com.moneyfirewall.reporting;

import com.moneyfirewall.service.ReportService;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ExcelReportExporter {
    public byte[] export(ReportTables t) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle yellowStyle = wb.createCellStyle();
            yellowStyle.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle greenStyle = wb.createCellStyle();
            greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle moneyStyle = wb.createCellStyle();
            moneyStyle.setDataFormat(wb.createDataFormat().getFormat("# ##0.00"));

            XSSFSheet summarySheet = wb.createSheet("Summary");
            writeSheet(summarySheet, t.summary(), summaryRowStyle(wb, t.summary()), yellowStyle, greenStyle, moneyStyle);
            applySummaryGrouping(summarySheet, t.summary());

            XSSFSheet byCategorySheet = wb.createSheet("ByCategory");
            writeSheet(byCategorySheet, t.byCategory(), byCategoryRowStyle(wb, t.byCategory()), yellowStyle, greenStyle, moneyStyle);
            applyByCategoryGrouping(byCategorySheet, t.byCategory());

            XSSFSheet byMemberSheet = wb.createSheet("ByMember");
            writeSheet(byMemberSheet, t.byMember(), null, yellowStyle, greenStyle, moneyStyle);

            XSSFSheet transactionsSheet = wb.createSheet("Transactions");
            writeSheet(transactionsSheet, t.transactions(), transactionsRowStyle(wb, t.transactions()), yellowStyle, greenStyle, moneyStyle);

            // Formulas (Summary metrics/category breakdown, ByCategory subtotals) reference other
            // sheets/ranges, so evaluate only once every sheet is populated, and autosize afterwards
            // so column widths reflect computed values rather than raw formula text.
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            autoSizeColumns(summarySheet, t.summary());
            autoSizeColumns(byCategorySheet, t.byCategory());
            autoSizeColumns(byMemberSheet, t.byMember());
            autoSizeColumns(transactionsSheet, t.transactions());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void autoSizeColumns(XSSFSheet sheet, List<List<Object>> rows) {
        int max = rows.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < max; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void applySummaryGrouping(XSSFSheet sheet, List<List<Object>> rows) {
        ReportService.computeSummaryCategoryOutline(rows)
                .forEach(r -> sheet.groupRow(r.startRow(), r.endRowInclusive()));
    }

    private void applyByCategoryGrouping(XSSFSheet sheet, List<List<Object>> rows) {
        List<ReportService.RowRange> ranges = ReportService.computeByCategoryOutline(rows);
        ranges.stream().filter(r -> r.level() == 1)
                .forEach(r -> sheet.groupRow(r.startRow(), r.endRowInclusive()));
        ranges.stream().filter(r -> r.level() == 2)
                .forEach(r -> sheet.groupRow(r.startRow(), r.endRowInclusive()));
    }

    private void writeSheet(XSSFSheet sheet, List<List<Object>> rows, RowStyle rowStyle, CellStyle yellowStyle, CellStyle greenStyle, CellStyle moneyStyle) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            List<Object> cols = rows.get(r);
            for (int c = 0; c < cols.size(); c++) {
                Cell cell = row.createCell(c);
                Object v = cols.get(c);
                boolean isMoneyCell = r > 0 && (v instanceof Number || v instanceof FormulaCell);
                if (v instanceof ColoredCell cc) {
                    writeCellValue(cell, cc.value());
                    cell.setCellStyle(cc.color() == ColoredCell.Color.YELLOW ? yellowStyle : greenStyle);
                } else {
                    writeCellValue(cell, v);
                    // Apply money format to numeric values and formulas (skip header row)
                    if (isMoneyCell) {
                        cell.setCellStyle(moneyStyle);
                    }
                }
            }
            if (rowStyle != null && r > 0) {
                CellStyle style = rowStyle.styleFor(cols);
                if (style != null) {
                    for (int c = 0; c < cols.size(); c++) {
                        // Per-cell colors (rate/converted-amount) win over the row-level style.
                        if (!(cols.get(c) instanceof ColoredCell)) {
                            row.getCell(c).setCellStyle(style);
                        }
                    }
                }
            }
        }
    }

    private void writeCellValue(Cell cell, Object v) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof FormulaCell f) {
            cell.setCellFormula(f.expression());
        } else if (v instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(v.toString());
        }
    }

    private RowStyle summaryRowStyle(XSSFWorkbook wb, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        CellStyle subtotal = wb.createCellStyle();
        subtotal.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        subtotal.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        subtotal.setDataFormat(wb.createDataFormat().getFormat("# ##0.00"));
        Font bold = wb.createFont();
        bold.setBold(true);
        subtotal.setFont(bold);

        return cols -> {
            if (cols.size() < 2 || !ReportService.BY_CATEGORY_SUBTOTAL.equals(String.valueOf(cols.get(1)))) {
                return null;
            }
            return subtotal;
        };
    }

    private RowStyle byCategoryRowStyle(XSSFWorkbook wb, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        int nicknameIdx = rows.getFirst().indexOf("nickname");
        if (nicknameIdx < 0) {
            return null;
        }

        CellStyle subtotal = wb.createCellStyle();
        subtotal.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        subtotal.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        subtotal.setDataFormat(wb.createDataFormat().getFormat("# ##0.00"));
        Font bold = wb.createFont();
        bold.setBold(true);
        subtotal.setFont(bold);

        CellStyle subSubtotal = wb.createCellStyle();
        subSubtotal.setDataFormat(wb.createDataFormat().getFormat("# ##0.00"));
        Font italic = wb.createFont();
        italic.setItalic(true);
        subSubtotal.setFont(italic);

        return cols -> {
            Object nickname = nicknameIdx < cols.size() ? cols.get(nicknameIdx) : null;
            String nn = String.valueOf(nickname);
            if (ReportService.BY_CATEGORY_SUBTOTAL.equals(nn)) {
                return subtotal;
            }
            if (ReportService.BY_SUBCATEGORY_SUBTOTAL.equals(nn)) {
                return subSubtotal;
            }
            return null;
        };
    }

    private RowStyle transactionsRowStyle(XSSFWorkbook wb, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<Object> header = rows.getFirst();
        int directionIdx = header.indexOf("Тип");
        if (directionIdx < 0) {
            return null;
        }

        CellStyle grayText = wb.createCellStyle();
        Font grayFont = wb.createFont();
        grayFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        grayText.setFont(grayFont);

        CellStyle greenBack = wb.createCellStyle();
        greenBack.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenBack.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        return cols -> {
            Object dir = directionIdx < cols.size() ? cols.get(directionIdx) : null;
            String dirStr = String.valueOf(dir);
            if ("Перевод".equals(dirStr)) {
                return grayText;
            }
            if ("Доход".equals(dirStr)) {
                return greenBack;
            }
            return null;
        };
    }

    @FunctionalInterface
    private interface RowStyle {
        CellStyle styleFor(List<Object> cols);
    }
}


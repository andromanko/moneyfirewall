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
            writeSheet(wb.createSheet("Summary"), t.summary(), null);
            writeSheet(wb.createSheet("ByCategory"), t.byCategory(), byCategoryRowStyle(wb, t.byCategory()));
            writeSheet(wb.createSheet("ByMember"), t.byMember(), null);
            writeSheet(wb.createSheet("Transactions"), t.transactions(), transactionsRowStyle(wb, t.transactions()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeSheet(XSSFSheet sheet, List<List<Object>> rows, RowStyle rowStyle) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            List<Object> cols = rows.get(r);
            for (int c = 0; c < cols.size(); c++) {
                Cell cell = row.createCell(c);
                Object v = cols.get(c);
                if (v == null) {
                    cell.setBlank();
                } else if (v instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else {
                    cell.setCellValue(v.toString());
                }
            }
            if (rowStyle != null && r > 0) {
                CellStyle style = rowStyle.styleFor(cols);
                if (style != null) {
                    for (int c = 0; c < cols.size(); c++) {
                        row.getCell(c).setCellStyle(style);
                    }
                }
            }
        }
        int max = rows.stream().mapToInt(java.util.List::size).max().orElse(0);
        for (int i = 0; i < max; i++) {
            sheet.autoSizeColumn(i);
        }
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
        Font bold = wb.createFont();
        bold.setBold(true);
        subtotal.setFont(bold);

        return cols -> {
            Object nickname = nicknameIdx < cols.size() ? cols.get(nicknameIdx) : null;
            return ReportService.BY_CATEGORY_SUBTOTAL.equals(String.valueOf(nickname)) ? subtotal : null;
        };
    }

    private RowStyle transactionsRowStyle(XSSFWorkbook wb, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<Object> header = rows.getFirst();
        int directionIdx = header.indexOf("direction");
        int isTransferIdx = header.indexOf("isTransfer");
        if (directionIdx < 0 || isTransferIdx < 0) {
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
            Object isTransfer = isTransferIdx < cols.size() ? cols.get(isTransferIdx) : null;
            if (Boolean.TRUE.equals(isTransfer) || "true".equalsIgnoreCase(String.valueOf(isTransfer))) {
                return grayText;
            }
            Object dir = directionIdx < cols.size() ? cols.get(directionIdx) : null;
            if ("INCOME".equalsIgnoreCase(String.valueOf(dir))) {
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


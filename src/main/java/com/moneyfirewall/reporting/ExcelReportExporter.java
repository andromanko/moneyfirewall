package com.moneyfirewall.reporting;

import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ExcelReportExporter {
    public byte[] export(ReportTables t) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeSheet(wb.createSheet("Summary"), t.summary());
            writeSheet(wb.createSheet("ByCategory"), t.byCategory());
            writeSheet(wb.createSheet("ByMember"), t.byMember());
            writeSheet(wb.createSheet("Transactions"), t.transactions());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeSheet(XSSFSheet sheet, java.util.List<java.util.List<Object>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            java.util.List<Object> cols = rows.get(r);
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
        }
        int max = rows.stream().mapToInt(java.util.List::size).max().orElse(0);
        for (int i = 0; i < max; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}


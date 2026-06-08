package com.moneyfirewall.reporting;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AddSheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BooleanCondition;
import com.google.api.services.sheets.v4.model.Color;
import com.google.api.services.sheets.v4.model.ConditionalFormatRule;
import com.google.api.services.sheets.v4.model.DeleteConditionalFormatRuleRequest;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.SpreadsheetProperties;
import com.google.api.services.sheets.v4.model.TextFormat;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.AddConditionalFormatRuleRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.moneyfirewall.config.MoneyFirewallProperties;
import com.moneyfirewall.service.ReportService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GoogleSheetsExporter {
    private final MoneyFirewallProperties props;

    public GoogleSheetsExporter(MoneyFirewallProperties props) {
        this.props = props;
    }

    public ExportResult export(String title, ReportTables tables) {
        if (props.google().serviceAccountJson() == null || props.google().serviceAccountJson().isBlank()) {
            throw new IllegalStateException("No GOOGLE_SERVICE_ACCOUNT_JSON");
        }
        try {
            NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();

            GoogleCredential cred = GoogleCredential.fromStream(
                            new ByteArrayInputStream(props.google().serviceAccountJson().getBytes(StandardCharsets.UTF_8)))
                    .createScoped(List.of(SheetsScopes.SPREADSHEETS));

            Sheets sheets = new Sheets.Builder(transport, jsonFactory, cred).setApplicationName("moneyfirewall").build();

            Spreadsheet spreadsheet = new Spreadsheet()
                    .setProperties(new SpreadsheetProperties().setTitle(title));
            Spreadsheet created = sheets.spreadsheets().create(spreadsheet).execute();
            String spreadsheetId = created.getSpreadsheetId();

            ensureSheets(sheets, spreadsheetId, List.of("Summary", "ByCategory", "ByMember", "Transactions"));

            writeValues(sheets, spreadsheetId, "Summary", tables.summary());
            writeValues(sheets, spreadsheetId, "ByCategory", tables.byCategory());
            writeValues(sheets, spreadsheetId, "ByMember", tables.byMember());
            writeValues(sheets, spreadsheetId, "Transactions", tables.transactions());
            applyByCategoryFormatting(sheets, spreadsheetId);
            applyTransactionsFormatting(sheets, spreadsheetId);

            return new ExportResult(spreadsheetId, "https://docs.google.com/spreadsheets/d/" + spreadsheetId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void ensureSheets(Sheets sheets, String spreadsheetId, List<String> names) throws Exception {
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId).execute();
        List<String> existing = ss.getSheets().stream().map(s -> s.getProperties().getTitle()).toList();
        List<Request> req = new ArrayList<>();
        for (String name : names) {
            if (existing.contains(name)) {
                continue;
            }
            req.add(new Request().setAddSheet(new AddSheetRequest().setProperties(new SheetProperties().setTitle(name))));
        }
        if (!req.isEmpty()) {
            sheets.spreadsheets().batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(req)).execute();
        }
    }

    private void applyByCategoryFormatting(Sheets sheets, String spreadsheetId) throws Exception {
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId).setIncludeGridData(false).execute();
        Sheet sheet = ss.getSheets().stream()
                .filter(s -> "ByCategory".equals(s.getProperties().getTitle()))
                .findFirst()
                .orElse(null);
        if (sheet == null) {
            return;
        }
        int sheetId = sheet.getProperties().getSheetId();

        List<Request> req = new ArrayList<>();
        if (sheet.getConditionalFormats() != null) {
            for (int i = sheet.getConditionalFormats().size() - 1; i >= 0; i--) {
                req.add(new Request().setDeleteConditionalFormatRule(
                        new DeleteConditionalFormatRuleRequest().setSheetId(sheetId).setIndex(i)));
            }
        }

        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(1)
                .setStartColumnIndex(0)
                .setEndColumnIndex(4);

        ConditionalFormatRule subtotal = new ConditionalFormatRule()
                .setRanges(List.of(range))
                .setBooleanRule(new com.google.api.services.sheets.v4.model.BooleanRule()
                        .setCondition(new BooleanCondition()
                                .setType("CUSTOM_FORMULA")
                                .setValues(List.of(new com.google.api.services.sheets.v4.model.ConditionValue()
                                        .setUserEnteredValue("=$C1=\"" + ReportService.BY_CATEGORY_SUBTOTAL + "\""))))
                        .setFormat(new CellFormat()
                                .setBackgroundColor(new Color().setRed(0.9f).setGreen(0.9f).setBlue(0.9f))
                                .setTextFormat(new TextFormat().setBold(true))));

        req.add(new Request().setAddConditionalFormatRule(new AddConditionalFormatRuleRequest().setRule(subtotal).setIndex(0)));
        sheets.spreadsheets().batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(req)).execute();
    }

    private void applyTransactionsFormatting(Sheets sheets, String spreadsheetId) throws Exception {
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId).setIncludeGridData(false).execute();
        Sheet txSheet = ss.getSheets().stream()
                .filter(s -> "Transactions".equals(s.getProperties().getTitle()))
                .findFirst()
                .orElse(null);
        if (txSheet == null) {
            return;
        }
        int sheetId = txSheet.getProperties().getSheetId();

        List<Request> req = new ArrayList<>();
        if (txSheet.getConditionalFormats() != null) {
            for (int i = txSheet.getConditionalFormats().size() - 1; i >= 0; i--) {
                req.add(new Request().setDeleteConditionalFormatRule(
                        new DeleteConditionalFormatRuleRequest().setSheetId(sheetId).setIndex(i)));
            }
        }

        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(1)
                .setStartColumnIndex(0)
                .setEndColumnIndex(9);

        ConditionalFormatRule grayText = new ConditionalFormatRule()
                .setRanges(List.of(range))
                .setBooleanRule(new com.google.api.services.sheets.v4.model.BooleanRule()
                        .setCondition(new BooleanCondition()
                                .setType("CUSTOM_FORMULA")
                                .setValues(List.of(new com.google.api.services.sheets.v4.model.ConditionValue()
                                        .setUserEnteredValue("=$I1=TRUE"))))
                        .setFormat(new CellFormat()
                                .setTextFormat(new TextFormat()
                                        .setForegroundColor(new Color().setRed(0.5f).setGreen(0.5f).setBlue(0.5f)))));

        ConditionalFormatRule greenBack = new ConditionalFormatRule()
                .setRanges(List.of(range))
                .setBooleanRule(new com.google.api.services.sheets.v4.model.BooleanRule()
                        .setCondition(new BooleanCondition()
                                .setType("CUSTOM_FORMULA")
                                .setValues(List.of(new com.google.api.services.sheets.v4.model.ConditionValue()
                                        .setUserEnteredValue("=AND($B1=\"INCOME\",$I1<>TRUE)"))))
                        .setFormat(new CellFormat()
                                .setBackgroundColor(new Color().setRed(0.8f).setGreen(1.0f).setBlue(0.8f))));

        req.add(new Request().setAddConditionalFormatRule(new AddConditionalFormatRuleRequest().setRule(grayText).setIndex(0)));
        req.add(new Request().setAddConditionalFormatRule(new AddConditionalFormatRuleRequest().setRule(greenBack).setIndex(1)));

        sheets.spreadsheets().batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(req)).execute();
    }

    private void writeValues(Sheets sheets, String spreadsheetId, String sheetName, List<List<Object>> rows) throws Exception {
        List<List<Object>> safe = rows.stream().map(r -> r.stream().map(v -> v == null ? "" : v).toList()).toList();
        ValueRange vr = new ValueRange().setValues(new ArrayList<>(safe));
        sheets.spreadsheets().values()
                .update(spreadsheetId, sheetName + "!A1", vr)
                .setValueInputOption("RAW")
                .execute();
    }

    public record ExportResult(String spreadsheetId, String url) {}
}


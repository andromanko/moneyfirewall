package com.moneyfirewall.importing;

import com.moneyfirewall.domain.ImportFileType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class SimplePdfStatementParser implements BankStatementParser {
    private static final Pattern LINE = Pattern.compile("(?<date>\\d{4}-\\d{2}-\\d{2})\\s+?(?<amount>-?\\d+[\\.,]\\d{2})\\s+(?<cur>[A-Z]{3})\\s+(?<cp>.+)");

    @Override
    public boolean supports(String bankCode, ImportFileType fileType) {
        return "generic".equalsIgnoreCase(bankCode) && fileType == ImportFileType.PDF;
    }

    @Override
    public List<ParsedOperation> parse(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            List<ParsedOperation> res = new ArrayList<>();
            for (String line : text.split("\\R")) {
                Matcher m = LINE.matcher(line.trim());
                if (!m.matches()) {
                    continue;
                }
                Instant occurredAt = LocalDate.parse(m.group("date")).atStartOfDay().toInstant(ZoneOffset.UTC);
                BigDecimal amount = new BigDecimal(m.group("amount").replace(',', '.'));
                String currency = m.group("cur");
                String counterparty = m.group("cp").trim();
                String dir = amount.signum() >= 0 ? "INCOME" : "EXPENSE";
                res.add(new ParsedOperation(occurredAt, amount.abs(), currency, dir, "Imported", counterparty, null));
            }
            return res;
        }
    }
}


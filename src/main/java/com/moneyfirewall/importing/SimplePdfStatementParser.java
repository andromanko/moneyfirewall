package com.moneyfirewall.importing;

import com.moneyfirewall.domain.ImportFileType;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SimplePdfStatementParser implements BankStatementParser {
    private static final Logger log = LoggerFactory.getLogger(SimplePdfStatementParser.class);
    private static final Pattern LINE = Pattern.compile("(?<date>\\d{4}-\\d{2}-\\d{2})\\s+?(?<amount>-?\\d+[\\.,]\\d{2})\\s+(?<cur>[A-Z]{3})\\s+(?<cp>.+)");
    private static final Pattern MTBANK_TX_START = Pattern.compile("^T\\s+(?<date>\\d{2}\\.\\d{2}\\.\\d{4})$");
    private static final Pattern MTBANK_REG_LINE = Pattern.compile("^(?<t>\\d{2}:\\d{2}:\\d{2})\\s+(?<d2>\\d{2}\\.\\d{2}\\.\\d{4})\\b");
    private static final Pattern CARD_AND_MERCHANT = Pattern.compile("^\\d{4,}\\*{2,}\\d{2,}\\s+(?<m>.+)$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern AMOUNT = Pattern.compile("^\\d+[\\.,]\\d{2}$");
    private static final Pattern SIGN = Pattern.compile("^[+-]$");
    private static final Pattern OPLATI_DATE = Pattern.compile("^\\d{2}\\.\\d{2}\\.\\d{4}$");
    private static final Pattern OPLATI_TIME = Pattern.compile("^\\d{2}:\\d{2}$");
    private static final Pattern OPLATI_ID = Pattern.compile("^\\d{6,}$");
    private static final DateTimeFormatter MTBANK_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd.MM.uuuu")
            .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter MTBANK_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("HH:mm:ss")
            .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter OPLATI_DATE_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd.MM.uuuu")
            .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter OPLATI_TIME_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("HH:mm")
            .toFormatter(Locale.ROOT);

    @Override
    public boolean supports(String bankCode, ImportFileType fileType) {
        if (fileType != ImportFileType.PDF) {
            return false;
        }
        String c = bankCode == null ? "" : bankCode.trim();
        return "generic".equalsIgnoreCase(c)
                || "mtbank".equalsIgnoreCase(c)
                || "oplati".equalsIgnoreCase(c);
    }

    @Override
    public List<ParsedOperation> parse(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            int pages = doc.getNumberOfPages();
            log.debug("pdf extract pages={} textChars={}", pages, text.length());
            List<ParsedOperation> mtb = parseMtbank(text);
            if (!mtb.isEmpty()) {
                log.debug("pdf branch=MTBank operations={}", mtb.size());
                return mtb;
            }
            List<ParsedOperation> oplati = parseOplati(text);
            if (!oplati.isEmpty()) {
                log.debug("pdf branch=OPLATI operations={}", oplati.size());
                return oplati;
            }
            List<ParsedOperation> generic = parseGenericLines(text);
            log.debug("pdf branch=genericLines operations={}", generic.size());
            return generic;
        }
    }

    private List<ParsedOperation> parseGenericLines(String text) {
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

    private List<ParsedOperation> parseMtbank(String text) {
        if (!text.contains("ЗАО «МТБанк»") && !text.contains("Выписка по счету")) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\\R")) {
            String t = l == null ? "" : l.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }

        List<List<String>> blocks = new ArrayList<>();
        List<String> cur = null;
        for (String l : lines) {
            if (MTBANK_TX_START.matcher(l).matches()) {
                if (cur != null && !cur.isEmpty()) {
                    blocks.add(cur);
                }
                cur = new ArrayList<>();
            }
            if (cur != null) {
                cur.add(l);
            }
        }
        if (cur != null && !cur.isEmpty()) {
            blocks.add(cur);
        }

        List<ParsedOperation> res = new ArrayList<>();
        for (List<String> b : blocks) {
            ParsedOperation op = parseMtbankBlock(b);
            if (op != null) {
                res.add(op);
            }
        }
        return res;
    }

    private ParsedOperation parseMtbankBlock(List<String> b) {
        LocalDate date = null;
        Matcher start = MTBANK_TX_START.matcher(b.getFirst());
        if (start.matches()) {
            try {
                date = LocalDate.parse(start.group("date"), MTBANK_DATE);
            } catch (DateTimeException ignored) {
                return null;
            }
        }
        if (date == null) {
            return null;
        }

        String currency = null;
        int currencyIdx = -1;
        for (int i = 0; i < b.size(); i++) {
            if (CURRENCY.matcher(b.get(i)).matches()) {
                currency = b.get(i);
                currencyIdx = i;
                break;
            }
        }
        if (currency == null) {
            return null;
        }

        BigDecimal opAmount = null;
        BigDecimal accountAmount = null;
        for (int i = currencyIdx + 1; i < b.size(); i++) {
            if (AMOUNT.matcher(b.get(i)).matches()) {
                opAmount = toAmount(b.get(i));
                if (i + 1 < b.size() && AMOUNT.matcher(b.get(i + 1)).matches()) {
                    accountAmount = toAmount(b.get(i + 1));
                }
                break;
            }
        }
        if (opAmount == null) {
            return null;
        }
        if (accountAmount == null) {
            accountAmount = opAmount;
        }

        String sign = null;
        for (int i = currencyIdx + 1; i < b.size(); i++) {
            if (SIGN.matcher(b.get(i)).matches()) {
                sign = b.get(i);
                break;
            }
        }
        String dir = "+".equals(sign) ? "INCOME" : "-".equals(sign) ? "EXPENSE" : "EXPENSE";

        String counterparty = extractMtbankCounterparty(b, currencyIdx);
        String description = extractMtbankDescription(b, currencyIdx);

        LocalDate occDate = date;
        LocalTime occTime = LocalTime.MIDNIGHT;
        if (b.size() > 1) {
            Matcher reg = MTBANK_REG_LINE.matcher(b.get(1).trim());
            if (reg.find()) {
                try {
                    occTime = LocalTime.parse(reg.group("t"), MTBANK_TIME);
                } catch (DateTimeException ignored) {
                }
                try {
                    occDate = LocalDate.parse(reg.group("d2"), MTBANK_DATE);
                } catch (DateTimeException ignored) {
                }
            }
        }
        Instant occurredAt = occDate.atTime(occTime).toInstant(ZoneOffset.UTC);
        BigDecimal amountByn = accountAmount.abs();
        return new ParsedOperation(occurredAt, amountByn, "BYN", dir, "MTBank", counterparty, description);
    }

    private BigDecimal toAmount(String s) {
        return new BigDecimal(s.replace(',', '.'));
    }

    private String extractMtbankCounterparty(List<String> b, int currencyIdx) {
        int stop = Math.min(currencyIdx, b.size());
        int startIdx = 1;
        for (int i = 0; i < stop; i++) {
            Matcher m = CARD_AND_MERCHANT.matcher(b.get(i));
            if (m.matches()) {
                String first = m.group("m").trim();
                StringBuilder sb = new StringBuilder(first);
                for (int j = i + 1; j < stop; j++) {
                    String l = b.get(j);
                    if (CURRENCY.matcher(l).matches() || AMOUNT.matcher(l).matches() || MTBANK_TX_START.matcher(l).matches()) {
                        break;
                    }
                    if (looksLikeDescriptionStart(l)) {
                        break;
                    }
                    sb.append(" ").append(l);
                }
                return sb.toString().trim();
            }
        }
        for (int i = startIdx; i < stop; i++) {
            String l = b.get(i);
            if (looksLikeDescriptionStart(l) || CURRENCY.matcher(l).matches() || AMOUNT.matcher(l).matches()) {
                continue;
            }
            if (l.startsWith("MP2P") || l.startsWith("MP2B")) {
                return l.trim();
            }
        }
        return "Imported";
    }

    private String extractMtbankDescription(List<String> b, int currencyIdx) {
        int stop = Math.min(currencyIdx, b.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stop; i++) {
            String l = b.get(i);
            if (!looksLikeDescriptionStart(l) && !looksLikeDescriptionCont(l)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(l);
        }
        String s = sb.toString().trim();
        return s.isBlank() ? null : s;
    }

    private boolean looksLikeDescriptionStart(String l) {
        return l.startsWith("Оплата") || l.startsWith("Пополнение") || l.startsWith("Списание");
    }

    private boolean looksLikeDescriptionCont(String l) {
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if ((c >= 'А' && c <= 'я') || c == 'ё' || c == 'Ё') {
                return true;
            }
        }
        return false;
    }

    private List<ParsedOperation> parseOplati(String text) {
        if (!text.contains("Белинвестбанк") && !text.contains("электронного кошелька") && !text.contains("OPLATI")) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        for (String l : text.split("\\R")) {
            String t = l == null ? "" : l.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }

        List<List<String>> blocks = new ArrayList<>();
        List<String> cur = null;
        for (String l : lines) {
            if (l.startsWith("Итого") || l.startsWith("обороты") || l.startsWith("Остаток на")) {
                break;
            }
            if (OPLATI_DATE.matcher(l).matches()) {
                if (cur != null && !cur.isEmpty()) {
                    blocks.add(cur);
                }
                cur = new ArrayList<>();
            }
            if (cur != null) {
                cur.add(l);
            }
        }
        if (cur != null && !cur.isEmpty()) {
            blocks.add(cur);
        }

        List<ParsedOperation> res = new ArrayList<>();
        for (List<String> b : blocks) {
            ParsedOperation op = parseOplatiBlock(b);
            if (op != null) {
                res.add(op);
            }
        }
        return res;
    }

    private ParsedOperation parseOplatiBlock(List<String> b) {
        if (b.isEmpty() || !OPLATI_DATE.matcher(b.getFirst()).matches()) {
            return null;
        }

        LocalDate date;
        try {
            date = LocalDate.parse(b.getFirst(), OPLATI_DATE_FMT);
        } catch (DateTimeException ignored) {
            return null;
        }

        String time = null;
        String id = null;
        for (int i = 1; i < b.size(); i++) {
            String l = b.get(i);
            if (time == null && OPLATI_TIME.matcher(l).matches()) {
                time = l;
                continue;
            }
            if (time != null && id == null && OPLATI_ID.matcher(l).matches()) {
                id = l;
                break;
            }
        }
        if (time == null) {
            return null;
        }

        Instant occurredAt = date.atTime(java.time.LocalTime.parse(time, OPLATI_TIME_FMT)).toInstant(ZoneOffset.UTC);

        int currencyIdx = -1;
        for (int i = 0; i < b.size(); i++) {
            if ("BYN".equals(b.get(i))) {
                currencyIdx = i;
                break;
            }
        }
        if (currencyIdx < 0) {
            return null;
        }

        BigDecimal amount = null;
        for (int i = currencyIdx - 1; i >= 0; i--) {
            if (AMOUNT.matcher(b.get(i)).matches()) {
                amount = toAmount(b.get(i));
                break;
            }
        }
        if (amount == null) {
            for (int i = 0; i < currencyIdx; i++) {
                if (looksLikeNumber(b.get(i))) {
                    amount = new BigDecimal(b.get(i).replace(',', '.'));
                    break;
                }
            }
        }
        if (amount == null) {
            return null;
        }

        String joined = String.join(" ", b);
        String dir = joined.toLowerCase(Locale.ROOT).contains("пополн") ? "INCOME" : "EXPENSE";

        String counterparty = extractOplatiCounterparty(b, currencyIdx);
        String description = extractOplatiDescription(b, currencyIdx, id);

        return new ParsedOperation(occurredAt, amount.abs(), "BYN", dir, "OPLATI", counterparty, description);
    }

    private boolean looksLikeNumber(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        boolean seenDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                seenDigit = true;
                continue;
            }
            if (c == '.' || c == ',') {
                continue;
            }
            return false;
        }
        return seenDigit;
    }

    private String extractOplatiDescription(List<String> b, int currencyIdx, String id) {
        int start = 1;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < currencyIdx; i++) {
            String l = b.get(i);
            if (OPLATI_TIME.matcher(l).matches() || OPLATI_ID.matcher(l).matches()) {
                continue;
            }
            if (id != null && id.equals(l)) {
                continue;
            }
            if (AMOUNT.matcher(l).matches() || looksLikeNumber(l)) {
                continue;
            }
            if ("BYN".equals(l)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(l);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.isBlank() ? null : s;
    }

    private String extractOplatiCounterparty(List<String> b, int currencyIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = currencyIdx + 1; i < b.size(); i++) {
            String l = b.get(i);
            if (l.startsWith("--") && l.contains("of")) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(l);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.isBlank()) {
            return "Imported";
        }
        int idx = s.indexOf("Оплата");
        if (idx == 0) {
            int cut = s.indexOf(' ', "Оплата".length());
            if (cut > 0) {
                String tail = s.substring(cut).trim();
                return tail.isBlank() ? "Imported" : tail;
            }
        }
        return s;
    }
}


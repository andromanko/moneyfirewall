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
    private static final Pattern CARD_GLUED_MERCHANT = Pattern.compile("^\\d{4,}\\*{2,}\\d{2,}(?<m>[A-Za-z].+)$");
    private static final Pattern CARD_ONLY = Pattern.compile("^\\d{4,}\\*+\\d{2,}$");
    public static final String MTBANK_MINSK_COUNTERPARTY = "MTBANK MINSK BY";
    private static final String GOODS_SERVICES_PREFIX = "товаров/услуг ";
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern AMOUNT = Pattern.compile("^\\d+[\\.,]\\d{2}$");
    private static final Pattern SIGN = Pattern.compile("^[+-]$");
    private static final Pattern OPLATI_DATE = Pattern.compile("^\\d{2}\\.\\d{2}\\.\\d{4}$");
    private static final Pattern OPLATI_TIME = Pattern.compile("^\\d{2}:\\d{2}$");
    private static final Pattern OPLATI_ID = Pattern.compile("^\\d{6,}$");
    private static final Pattern OPLATI_ID_PREFIX = Pattern.compile("^(?<id>\\d{6,})\\b");
    private static final Pattern OPLATI_SUMMARY = Pattern.compile(
            "^(?<a1>\\d+(?:[\\.,]\\d{1,2})?)\\s+(?<a2>\\d+(?:[\\.,]\\d{1,2})?)\\s+(?<cur>[A-Z]{3})(?:\\s+(?<desc>.*))?$");
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
            log.debug("pdf extract pages={} textChars={}", doc.getNumberOfPages(), text.length());
            return parseExtractedText(text);
        }
    }

    List<ParsedOperation> parseExtractedText(String text) {
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
        String counterparty = canonicalizeMtbankCounterparty(extractMtbankCounterparty(b, currencyIdx));
        String description = extractMtbankDescription(b, currencyIdx);

        String dir = "+".equals(sign) ? "INCOME" : "-".equals(sign) ? "EXPENSE" : "EXPENSE";
        if (isMtbankOutgoingTransfer(counterparty, description)) {
            dir = "EXPENSE";
        }

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
        return new ParsedOperation(occurredAt, opAmount.abs(), currency, dir, "MTBank", counterparty, description);
    }

    private BigDecimal toAmount(String s) {
        return new BigDecimal(s.replace(',', '.'));
    }

    private BigDecimal toAmountFlexible(String s) {
        if (s.contains(".") || s.contains(",")) {
            return toAmount(s);
        }
        return new BigDecimal(s);
    }

    private boolean isMtbankOutgoingTransfer(String counterparty, String description) {
        String cp = counterparty == null ? "" : counterparty.trim().toUpperCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (cp.startsWith("MP2P") || cp.startsWith("MP2B")) {
            return true;
        }
        return desc.contains("mp2p") || desc.contains("mp2b");
    }

    private String extractMtbankCounterparty(List<String> b, int currencyIdx) {
        int stop = Math.min(currencyIdx, b.size());

        for (int i = 0; i < stop; i++) {
            String line = b.get(i).trim();
            Matcher glued = CARD_GLUED_MERCHANT.matcher(line);
            if (glued.matches()) {
                return appendMtbankMerchantTail(b, i, stop, glued.group("m").trim());
            }
            Matcher m = CARD_AND_MERCHANT.matcher(line);
            if (m.matches()) {
                return appendMtbankMerchantTail(b, i, stop, m.group("m").trim());
            }
        }

        for (int i = 1; i < stop; i++) {
            if (CARD_ONLY.matcher(b.get(i)).matches() && i + 1 < stop) {
                String next = b.get(i + 1).trim();
                if (!isMtbankStructuralLine(next) && !looksLikeDescriptionStart(next)) {
                    return appendMtbankMerchantTail(b, i + 1, stop, next);
                }
            }
        }

        for (int i = 1; i < stop; i++) {
            String l = b.get(i).trim();
            if (l.startsWith("MP2P") || l.startsWith("MP2B")) {
                return l;
            }
        }

        List<String> merchantLines = new ArrayList<>();
        for (int i = 1; i < stop; i++) {
            String l = b.get(i).trim();
            if (isMtbankStructuralLine(l)) {
                continue;
            }
            if (looksLikeDescriptionStart(l)) {
                if (!merchantLines.isEmpty()) {
                    break;
                }
                String fromDescLine = counterpartyFromMtbankDescriptionLine(l);
                if (fromDescLine != null) {
                    return fromDescLine;
                }
                continue;
            }
            merchantLines.add(l);
        }
        if (!merchantLines.isEmpty()) {
            return String.join(" ", merchantLines).trim();
        }

        String fromDesc = counterpartyFromMtbankDescription(extractMtbankDescription(b, currencyIdx));
        if (fromDesc != null) {
            return fromDesc;
        }

        return "Imported";
    }

    public static String canonicalizeMtbankCounterparty(String counterparty) {
        if (counterparty == null || counterparty.isBlank()) {
            return counterparty;
        }
        String cp = stripMtbankCardPrefix(counterparty.trim());
        if (cp.toUpperCase(Locale.ROOT).contains("MTBANK MINSK")) {
            return MTBANK_MINSK_COUNTERPARTY;
        }
        return cp;
    }

    public static String stripMtbankCardPrefix(String line) {
        Matcher glued = CARD_GLUED_MERCHANT.matcher(line);
        if (glued.matches()) {
            return glued.group("m").trim();
        }
        Matcher spaced = CARD_AND_MERCHANT.matcher(line);
        if (spaced.matches()) {
            return spaced.group("m").trim();
        }
        return line;
    }

    private String appendMtbankMerchantTail(List<String> b, int fromIdx, int stop, String first) {
        StringBuilder sb = new StringBuilder(first);
        for (int j = fromIdx + 1; j < stop; j++) {
            String l = b.get(j);
            if (isMtbankStructuralLine(l) || looksLikeDescriptionStart(l)) {
                break;
            }
            sb.append(" ").append(l);
        }
        return sb.toString().trim();
    }

    private boolean isMtbankStructuralLine(String l) {
        if (l == null || l.isBlank()) {
            return true;
        }
        return MTBANK_TX_START.matcher(l).matches()
                || MTBANK_REG_LINE.matcher(l).matches()
                || CURRENCY.matcher(l).matches()
                || AMOUNT.matcher(l).matches()
                || SIGN.matcher(l).matches()
                || CARD_ONLY.matcher(l).matches();
    }

    private String counterpartyFromMtbankDescriptionLine(String line) {
        return counterpartyFromMtbankDescription(line);
    }

    private String counterpartyFromMtbankDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String desc = description.trim();
        int goodsIdx = desc.toLowerCase(Locale.ROOT).indexOf(GOODS_SERVICES_PREFIX);
        if (goodsIdx >= 0) {
            String tail = desc.substring(goodsIdx + GOODS_SERVICES_PREFIX.length()).trim();
            if (!tail.isBlank()) {
                return tail;
            }
        }
        if (desc.startsWith("Оплата ")) {
            String tail = desc.substring("Оплата ".length()).trim();
            if (!tail.isBlank() && !tail.startsWith("товаров")) {
                return tail;
            }
        }
        if (desc.startsWith("Пополнение ")) {
            String tail = desc.substring("Пополнение ".length()).trim();
            if (!tail.isBlank()) {
                return tail;
            }
        }
        if (desc.startsWith("Списание ")) {
            String tail = desc.substring("Списание ".length()).trim();
            if (!tail.isBlank()) {
                return tail;
            }
        }
        if (desc.length() > 2) {
            return desc;
        }
        return null;
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
            if (l.startsWith("Итого") || l.startsWith("обороты")) {
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
            if (time != null && id == null) {
                if (OPLATI_ID.matcher(l).matches()) {
                    id = l;
                    continue;
                }
                Matcher idm = OPLATI_ID_PREFIX.matcher(l);
                if (idm.lookingAt()) {
                    id = idm.group("id");
                }
            }
        }
        if (time == null) {
            return null;
        }

        Instant occurredAt = date.atTime(java.time.LocalTime.parse(time, OPLATI_TIME_FMT)).toInstant(ZoneOffset.UTC);

        int summaryIdx = -1;
        String summaryTail = null;
        BigDecimal amount = null;
        String summaryCurrency = null;
        for (int i = 0; i < b.size(); i++) {
            Matcher sm = OPLATI_SUMMARY.matcher(b.get(i));
            if (!sm.matches()) {
                continue;
            }
            summaryIdx = i;
            amount = toAmountFlexible(sm.group("a1"));
            summaryCurrency = sm.group("cur");
            String desc = sm.group("desc");
            summaryTail = desc == null || desc.isBlank() ? null : desc.trim();
            break;
        }

        int currencyIdx = summaryIdx;
        String currency = summaryCurrency;
        if (amount == null) {
            for (int i = 0; i < b.size(); i++) {
                if (CURRENCY.matcher(b.get(i)).matches()) {
                    currencyIdx = i;
                    currency = b.get(i);
                    break;
                }
            }
            if (currencyIdx < 0) {
                return null;
            }

            BigDecimal a1 = null;
            BigDecimal a2 = null;
            for (int i = currencyIdx - 1; i >= 0; i--) {
                String s = b.get(i);
                if (!(AMOUNT.matcher(s).matches() || looksLikeNumber(s))) {
                    continue;
                }
                BigDecimal v = toAmountFlexible(s);
                if (a1 == null) {
                    a1 = v;
                    continue;
                }
                a2 = v;
                break;
            }
            amount = a2 != null ? a2 : a1;
        }
        if (currency == null) {
            currency = "BYN";
        }

        if (amount == null) {
            return null;
        }

        String counterparty = extractOplatiCounterparty(b, currencyIdx, summaryTail);
        String description = extractOplatiDescription(b, currencyIdx, id, summaryTail);

        // Direction comes strictly from the "Тип платежа" column (captured in `description`), not
        // from the whole row: the "Детали операции" text for an outgoing ЕРИП payment often reads
        // "Пополнение дебетовой карты/счета ..." (topping up a DIFFERENT account), which used to be
        // misread as this wallet receiving money.
        String paymentType = description == null ? "" : description.trim().toLowerCase(Locale.ROOT);
        String dir = paymentType.startsWith("пополн") ? "INCOME" : "EXPENSE";

        return new ParsedOperation(occurredAt, amount.abs(), currency, dir, "OPLATI", counterparty, description);
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

    private String extractOplatiDescription(List<String> b, int currencyIdx, String id, String summaryTail) {
        int start = 1;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < currencyIdx; i++) {
            String l = b.get(i);
            if (OPLATI_TIME.matcher(l).matches()) {
                continue;
            }
            // The id and the first word of "Тип платежа" are often glued onto one PDF text line
            // (e.g. "496316234 Оплата"); strip just the id prefix instead of dropping the whole line,
            // otherwise "Оплата"/"Пополнение" is lost and direction detection has nothing to go on.
            String rest = l;
            if (id != null && l.startsWith(id)) {
                rest = l.substring(id.length()).trim();
            } else if (OPLATI_ID.matcher(l).matches()) {
                continue;
            }
            if (rest.isBlank()) {
                continue;
            }
            if (AMOUNT.matcher(rest).matches() || looksLikeNumber(rest)) {
                continue;
            }
            if (CURRENCY.matcher(rest).matches() || OPLATI_SUMMARY.matcher(rest).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(rest);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.isBlank() ? null : s;
    }

    private String extractOplatiCounterparty(List<String> b, int currencyIdx, String summaryTail) {
        StringBuilder sb = new StringBuilder();
        if (summaryTail != null && !summaryTail.isBlank()) {
            sb.append(summaryTail);
        }
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


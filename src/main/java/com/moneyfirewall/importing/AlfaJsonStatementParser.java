package com.moneyfirewall.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyfirewall.domain.ImportFileType;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AlfaJsonStatementParser implements BankStatementParser {
    private static final Logger log = LoggerFactory.getLogger(AlfaJsonStatementParser.class);
    private static final DateTimeFormatter ALFA_DATE_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("uuuuMMddHHmmss")
            .toFormatter(Locale.ROOT);

    private final ObjectMapper objectMapper;

    public AlfaJsonStatementParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String bankCode, ImportFileType fileType) {
        return "AlfaJson".equalsIgnoreCase(bankCode) && fileType == ImportFileType.JSON;
    }

    @Override
    public List<ParsedOperation> parse(byte[] bytes) throws Exception {
        JsonNode root = objectMapper.readTree(bytes);
        if (!root.has("items") || !root.get("items").isArray()) {
            throw new IllegalArgumentException("Unsupported Alfa JSON");
        }
        JsonNode items = root.get("items");
        List<ParsedOperation> res = new ArrayList<>();
        int skipped = 0;
        for (JsonNode item : items) {
            ParsedOperation op = parseItem(item);
            if (op != null) {
                res.add(op);
            } else {
                skipped++;
            }
        }
        log.debug("alfaJson items={} parsed={} skippedNoAmount={}", items.size(), res.size(), skipped);
        return res;
    }

    private ParsedOperation parseItem(JsonNode item) {
        JsonNode amountWrap = item.get("amount");
        if (amountWrap == null || amountWrap.isNull() || !amountWrap.has("amount")) {
            return null;
        }
        JsonNode amountNode = amountWrap.get("amount");
        if (amountNode == null || amountNode.isNull()) {
            return null;
        }
        BigDecimal amountSigned = amountNode.isBigDecimal()
                ? amountNode.decimalValue()
                : new BigDecimal(amountNode.asText());
        String currency = amountWrap.hasNonNull("postfix")
                ? amountWrap.get("postfix").asText().trim()
                : "BYN";
        if (currency.isEmpty()) {
            currency = "BYN";
        }
        String direction = amountSigned.signum() >= 0 ? "INCOME" : "EXPENSE";
        BigDecimal amount = amountSigned.abs();

        String title = item.hasNonNull("title") ? item.get("title").asText().trim() : "";
        String description = item.hasNonNull("description") ? item.get("description").asText().trim() : null;
        if (description != null && description.isEmpty()) {
            description = null;
        }

        Instant occurredAt = parseAlfaDate(item.get("date"));
        String counterparty = title.isEmpty() ? (description != null ? description : "Imported") : title;
        return new ParsedOperation(occurredAt, amount, currency.toUpperCase(Locale.ROOT), direction, "Alfa", counterparty, description);
    }

    private Instant parseAlfaDate(JsonNode dateNode) {
        if (dateNode == null || dateNode.isNull()) {
            return Instant.now();
        }
        String s = dateNode.asText().trim();
        if (s.length() != 14) {
            return Instant.now();
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, ALFA_DATE_TIME);
            return ldt.toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            return Instant.now();
        }
    }
}

package com.moneyfirewall.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyfirewall.domain.ImportFileType;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
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
public class MtbankJsonStatementParser implements BankStatementParser {
    private static final Logger log = LoggerFactory.getLogger(MtbankJsonStatementParser.class);
    private static final DateTimeFormatter TRANS_DATE_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .toFormatter(Locale.ROOT);

    private final ObjectMapper objectMapper;

    public MtbankJsonStatementParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String bankCode, ImportFileType fileType) {
        return fileType == ImportFileType.JSON && "mtbank".equalsIgnoreCase(bankCode == null ? "" : bankCode.trim());
    }

    @Override
    public List<ParsedOperation> parse(byte[] bytes) throws Exception {
        JsonNode root = objectMapper.readTree(bytes);
        List<JsonNode> operationNodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(operationNodes::add);
        } else if (root.has("data") && root.get("data").isArray()) {
            for (JsonNode block : root.get("data")) {
                if (block.has("operations") && block.get("operations").isArray()) {
                    block.get("operations").forEach(operationNodes::add);
                }
            }
        } else if (root.has("operations") && root.get("operations").isArray()) {
            root.get("operations").forEach(operationNodes::add);
        } else {
            throw new IllegalArgumentException("Unsupported MTBank JSON");
        }

        List<ParsedOperation> res = new ArrayList<>();
        int skippedFailed = 0;
        for (JsonNode op : operationNodes) {
            if (isFailed(op)) {
                skippedFailed++;
                continue;
            }
            res.add(parseOperation(op));
        }
        log.debug("mtbankJson operations={} parsed={} skippedFailed={}", operationNodes.size(), res.size(), skippedFailed);
        return res;
    }

    private boolean isFailed(JsonNode op) {
        if (op.hasNonNull("status") && "E".equalsIgnoreCase(op.get("status").asText().trim())) {
            return true;
        }
        return op.hasNonNull("error") && !op.get("error").asText().isBlank();
    }

    private ParsedOperation parseOperation(JsonNode op) {
        BigDecimal amount = op.hasNonNull("amount") ? new BigDecimal(op.get("amount").asText().trim()).abs() : BigDecimal.ZERO;
        String currency = op.hasNonNull("curr") ? op.get("curr").asText().trim().toUpperCase(Locale.ROOT) : "BYN";
        if (currency.isEmpty()) {
            currency = "BYN";
        }
        // MTBank's own "debit" flag is inverted from usual accounting terms here: "1" is money
        // added to the account (top-ups), "0" covers card/POS payments and failed attempts.
        String direction = op.hasNonNull("debitFlag") && "1".equals(op.get("debitFlag").asText().trim())
                ? "INCOME"
                : "EXPENSE";

        String place = op.hasNonNull("place") ? op.get("place").asText().trim() : "";
        String description = op.hasNonNull("description") ? op.get("description").asText().trim() : "";
        String counterparty = !place.isEmpty() ? place : (!description.isEmpty() ? description : "Imported");

        Instant occurredAt = parseOccurredAt(op);

        return new ParsedOperation(occurredAt, amount, currency, direction, "MTBank", counterparty, description.isEmpty() ? null : description);
    }

    private Instant parseOccurredAt(JsonNode op) {
        if (op.hasNonNull("transDate")) {
            String s = op.get("transDate").asText().trim();
            try {
                return LocalDateTime.parse(s, TRANS_DATE_TIME).toInstant(ZoneOffset.UTC);
            } catch (DateTimeException ignored) {
                // fall through to operationDate
            }
        }
        if (op.hasNonNull("operationDate")) {
            String s = op.get("operationDate").asText().trim();
            try {
                return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeException ignored) {
                // fall through to now
            }
        }
        return Instant.now();
    }
}

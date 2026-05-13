package com.moneyfirewall.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyfirewall.domain.ImportFileType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GenericJsonStatementParser implements BankStatementParser {
    private static final Logger log = LoggerFactory.getLogger(GenericJsonStatementParser.class);
    private final ObjectMapper objectMapper;

    public GenericJsonStatementParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String bankCode, ImportFileType fileType) {
        return "generic".equalsIgnoreCase(bankCode) && fileType == ImportFileType.JSON;
    }

    @Override
    public List<ParsedOperation> parse(byte[] bytes) throws Exception {
        JsonNode root = objectMapper.readTree(bytes);
        List<ParsedOperation> res = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                res.add(parseNode(n));
            }
            log.debug("json branch=rootArray operations={}", res.size());
        } else if (root.has("operations") && root.get("operations").isArray()) {
            for (JsonNode n : root.get("operations")) {
                res.add(parseNode(n));
            }
            log.debug("json branch=operations operations={}", res.size());
        } else {
            throw new IllegalArgumentException("Unsupported JSON");
        }
        return res;
    }

    private ParsedOperation parseNode(JsonNode n) {
        Instant occurredAt = parseInstant(n.get("occurredAt"), n.get("date"));
        BigDecimal amount = n.hasNonNull("amount") ? n.get("amount").decimalValue() : BigDecimal.ZERO;
        String currency = n.hasNonNull("currency") ? n.get("currency").asText() : "BYN";
        String direction = n.hasNonNull("direction") ? n.get("direction").asText() : (amount.signum() >= 0 ? "INCOME" : "EXPENSE");
        String accountName = n.hasNonNull("account") ? n.get("account").asText() : "Imported";
        String counterparty = n.hasNonNull("counterparty") ? n.get("counterparty").asText() : null;
        String description = n.hasNonNull("description") ? n.get("description").asText() : null;
        BigDecimal abs = amount.abs();
        return new ParsedOperation(occurredAt, abs, currency, direction.toUpperCase(), accountName, counterparty, description);
    }

    private Instant parseInstant(JsonNode occurredAt, JsonNode date) {
        if (occurredAt != null && !occurredAt.isNull()) {
            String s = occurredAt.asText();
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
            }
        }
        if (date != null && !date.isNull()) {
            String s = date.asText();
            try {
                return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
            }
        }
        return Instant.now();
    }
}


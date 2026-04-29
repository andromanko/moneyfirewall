package com.moneyfirewall.importing;

import java.math.BigDecimal;
import java.time.Instant;

public record ParsedOperation(
        Instant occurredAt,
        BigDecimal amount,
        String currency,
        String direction,
        String accountName,
        String counterpartyRaw,
        String description
) {}


package com.moneyfirewall.reporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ReportTables(
        List<List<Object>> summary,
        List<List<Object>> byCategory,
        List<List<Object>> byMember,
        List<List<Object>> transactions,
        Map<String, BigDecimal> incomeByCurrency,
        Map<String, BigDecimal> expenseByCurrency
) {}


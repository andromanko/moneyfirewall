package com.moneyfirewall.reporting;

import java.math.BigDecimal;
import java.util.List;

public record ReportTables(
        List<List<Object>> summary,
        List<List<Object>> byCategory,
        List<List<Object>> byMember,
        List<List<Object>> transactions,
        List<List<Object>> byHashtag,
        BigDecimal incomeTotal,
        BigDecimal expenseTotal,
        String currency
) {}


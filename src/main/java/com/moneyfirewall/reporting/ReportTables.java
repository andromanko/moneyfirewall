package com.moneyfirewall.reporting;

import java.util.List;

public record ReportTables(
        List<List<Object>> summary,
        List<List<Object>> byCategory,
        List<List<Object>> byMember,
        List<List<Object>> transactions
) {}


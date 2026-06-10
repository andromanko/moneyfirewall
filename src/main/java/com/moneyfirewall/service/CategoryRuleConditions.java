package com.moneyfirewall.service;

import java.math.BigDecimal;
import java.util.Locale;

public record CategoryRuleConditions(
        String pattern,
        String accountName,
        BigDecimal minAmount,
        BigDecimal exactAmount,
        boolean oncePerMonth,
        int priority
) {
    public static CategoryRuleConditions fromMatchText(String match) {
        String pattern = "";
        String accountName = null;
        BigDecimal minAmount = null;
        BigDecimal exactAmount = null;
        boolean oncePerMonth = false;
        int priority = 100;
        for (String token : match.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("account:")) {
                accountName = token.substring("account:".length()).trim();
            } else if (lower.startsWith("min:")) {
                minAmount = new BigDecimal(token.substring("min:".length()).trim());
            } else if (lower.startsWith("amount:")) {
                exactAmount = new BigDecimal(token.substring("amount:".length()).trim());
            } else if (lower.startsWith("prio:")) {
                priority = Integer.parseInt(token.substring("prio:".length()).trim());
            } else if ("once_month".equals(lower)) {
                oncePerMonth = true;
            } else {
                pattern = pattern.isEmpty() ? token : pattern + " " + token;
            }
        }
        return new CategoryRuleConditions(pattern, accountName, minAmount, exactAmount, oncePerMonth, priority);
    }

    public boolean hasConstraints() {
        return !pattern.isBlank()
                || accountName != null
                || minAmount != null
                || exactAmount != null;
    }
}

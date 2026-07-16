package com.moneyfirewall.reporting;

/**
 * Marks a single report-table cell that should get a specific background fill, independent of
 * whatever row-level style applies. Used to flag how a currency-converted amount was computed:
 * YELLOW = converted via an NBRB exchange rate, GREEN = converted via a known account balance
 * before/after difference (more accurate than a rate lookup).
 */
public record ColoredCell(Object value, Color color) {
    public enum Color {
        YELLOW, GREEN
    }
}

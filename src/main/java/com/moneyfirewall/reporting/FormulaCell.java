package com.moneyfirewall.reporting;

/**
 * Marks a report table cell whose value should be written as a live spreadsheet formula
 * (recalculated from the Transactions/ByCategory sheet data) instead of a literal value.
 * {@code expression} is the formula body without the leading "=".
 */
public record FormulaCell(String expression) {
}

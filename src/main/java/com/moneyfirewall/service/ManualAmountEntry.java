package com.moneyfirewall.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-line manual entry: an optional date, an amount, an optional currency (symbol or code, BYN
 * when omitted) and an optional free-text comment — e.g. {@code "100 USD обед"},
 * {@code "25,50 продукты"}, {@code "20$ такси"}, {@code "$100"}, {@code "40"},
 * {@code "15.08 100 такси"} (this year), {@code "15.08.2026 100 такси"}.
 *
 * <p>A currency code is only recognised from a known list rather than "any three letters", so a
 * comment like {@code "100 еда"} stays a comment instead of becoming a bogus currency.
 *
 * @param date null when no date was given in the text — the caller then defaults to today.
 */
public record ManualAmountEntry(LocalDate date, BigDecimal amount, String currency, String comment) {
    public static final String DEFAULT_CURRENCY = "BYN";

    private static final Map<String, String> SYMBOLS = Map.of(
            "$", "USD",
            "€", "EUR",
            "₽", "RUB",
            "£", "GBP",
            "Br", "BYN"
    );

    /** Recognised codes; anything else after the amount is treated as the comment. */
    private static final Set<String> CODES = Set.of(
            "BYN", "USD", "EUR", "RUB", "RUR", "PLN", "GBP", "CHF", "UAH", "KZT", "CNY",
            "USDT", "BTC", "ETH"
    );

    /** Legacy/alternate codes normalised to what the rate services actually publish. */
    private static final Map<String, String> ALIASES = Map.of("RUR", "RUB");

    private static final Pattern LEADING_SYMBOL = Pattern.compile("^([$€₽£]|Br)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile("^(\\d+(?:[.,]\\d{1,8})?)");
    private static final Pattern TRAILING_SYMBOL = Pattern.compile("^\\s*([$€₽£])");
    private static final Pattern WORD = Pattern.compile("^\\s+(\\S+)");
    private static final Pattern LEADING_DATE = Pattern.compile("^(\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}\\.\\d{1,2}\\.\\d{4}|\\d{1,2}\\.\\d{1,2})\\s+");

    public static Optional<ManualAmountEntry> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String rest = text.trim();
        if (rest.isEmpty()) {
            return Optional.empty();
        }

        LocalDate date = null;
        Matcher dateMatcher = LEADING_DATE.matcher(rest);
        if (dateMatcher.find()) {
            date = parseDateToken(dateMatcher.group(1));
            if (date != null) {
                rest = rest.substring(dateMatcher.end());
            }
        }

        String currency = null;
        Matcher leading = LEADING_SYMBOL.matcher(rest);
        if (leading.find()) {
            currency = SYMBOLS.get(canonicalSymbol(leading.group(1)));
            rest = rest.substring(leading.end());
        }

        Matcher amountMatcher = AMOUNT.matcher(rest);
        if (!amountMatcher.find()) {
            return Optional.empty();
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountMatcher.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (amount.signum() <= 0) {
            return Optional.empty();
        }
        rest = rest.substring(amountMatcher.end());

        // A symbol glued to the amount ("20$"), only if one wasn't already given as a prefix.
        if (currency == null) {
            Matcher trailing = TRAILING_SYMBOL.matcher(rest);
            if (trailing.find()) {
                currency = SYMBOLS.get(canonicalSymbol(trailing.group(1)));
                rest = rest.substring(trailing.end());
            }
        }

        // A separated code ("100 USD обед") — consumed only when it really is a known currency.
        if (currency == null) {
            Matcher word = WORD.matcher(rest);
            if (word.find()) {
                String candidate = word.group(1).toUpperCase(Locale.ROOT);
                if (CODES.contains(candidate)) {
                    currency = candidate;
                    rest = rest.substring(word.end());
                }
            }
        }

        String comment = rest.trim();
        return Optional.of(new ManualAmountEntry(
                date,
                amount,
                normalize(currency == null ? DEFAULT_CURRENCY : currency),
                comment.isEmpty() ? null : comment
        ));
    }

    private static String canonicalSymbol(String raw) {
        return "br".equalsIgnoreCase(raw) ? "Br" : raw;
    }

    private static String normalize(String code) {
        String upper = code.toUpperCase(Locale.ROOT);
        return ALIASES.getOrDefault(upper, upper);
    }

    /** {@code "15.08"} (day.month, current year), {@code "15.08.2026"} or {@code "2026-08-15"}; null if invalid. */
    private static LocalDate parseDateToken(String token) {
        try {
            if (token.contains("-")) {
                return LocalDate.parse(token, DateTimeFormatter.ofPattern("yyyy-M-d"));
            }
            String[] parts = token.split("\\.");
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            if (parts.length == 3) {
                return LocalDate.of(Integer.parseInt(parts[2]), month, day);
            }
            return LocalDate.of(LocalDate.now().getYear(), month, day);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}

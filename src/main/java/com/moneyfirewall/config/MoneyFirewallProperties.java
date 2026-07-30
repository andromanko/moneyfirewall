package com.moneyfirewall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moneyfirewall")
public record MoneyFirewallProperties(Telegram telegram, Google google, Rates rates) {
    public record Telegram(String token, String username) {}

    public record Google(String serviceAccountJson, String spreadsheetPrefix) {}

    public record Rates(Savings savings) {}

    /**
     * Price source for savings currencies NBRB doesn't publish (crypto). Fully templated so a
     * different exchange can be swapped in from config without code changes: {@code priceUrl} and
     * {@code symbol} accept {@code {symbol}}, {@code {base}} and {@code {quote}} placeholders, and
     * {@code priceField} names the JSON field holding the price.
     */
    public record Savings(String priceUrl, String symbol, String priceField, String quoteCurrency) {}
}


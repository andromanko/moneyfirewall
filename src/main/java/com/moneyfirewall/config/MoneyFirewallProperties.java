package com.moneyfirewall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moneyfirewall")
public record MoneyFirewallProperties(Telegram telegram, Google google) {
    public record Telegram(String token, String username) {}

    public record Google(String serviceAccountJson, String spreadsheetPrefix) {}
}


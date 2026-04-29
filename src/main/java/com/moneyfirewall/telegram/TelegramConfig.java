package com.moneyfirewall.telegram;

import com.moneyfirewall.config.MoneyFirewallProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
public class TelegramConfig {
    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${moneyfirewall.telegram.token:}')")
    public TelegramClient telegramClient(MoneyFirewallProperties props) {
        return new OkHttpTelegramClient(props.telegram().token());
    }
}


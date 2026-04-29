package com.moneyfirewall.telegram;

import com.moneyfirewall.config.MoneyFirewallProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;

@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${moneyfirewall.telegram.token:}')")
public class MoneyFirewallBot implements SpringLongPollingBot {
    private final MoneyFirewallProperties props;
    private final LongPollingUpdateConsumer consumer;

    public MoneyFirewallBot(MoneyFirewallProperties props, MoneyFirewallUpdateConsumer consumer) {
        this.props = props;
        this.consumer = consumer;
    }

    @Override
    public String getBotToken() {
        return props.telegram().token();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return consumer;
    }
}


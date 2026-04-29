package com.moneyfirewall.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.ByteArrayInputStream;

@Component
@ConditionalOnBean(TelegramClient.class)
public class TelegramSender {
    private final TelegramClient client;

    public TelegramSender(TelegramClient client) {
        this.client = client;
    }

    public void sendText(long chatId, String text) {
        try {
            SendMessage msg = SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .build();
            client.execute(msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void sendText(long chatId, String text, InlineKeyboardMarkup markup) {
        try {
            SendMessage msg = SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .replyMarkup(markup)
                    .build();
            client.execute(msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void sendDocument(long chatId, byte[] bytes, String filename, String caption) {
        try {
            InputFile f = new InputFile(new ByteArrayInputStream(bytes), filename);
            SendDocument msg = SendDocument.builder()
                    .chatId(Long.toString(chatId))
                    .document(f)
                    .caption(caption)
                    .build();
            client.execute(msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}


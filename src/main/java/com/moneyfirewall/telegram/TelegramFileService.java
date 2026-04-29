package com.moneyfirewall.telegram;

import com.moneyfirewall.config.MoneyFirewallProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
public class TelegramFileService {
    private final TelegramClient client;
    private final MoneyFirewallProperties props;
    private final HttpClient httpClient;

    public TelegramFileService(TelegramClient client, MoneyFirewallProperties props) {
        this.client = client;
        this.props = props;
        this.httpClient = HttpClient.newHttpClient();
    }

    public byte[] downloadByFileId(String fileId) {
        try {
            File f = client.execute(new GetFile(fileId));
            String path = f.getFilePath();
            String url = "https://api.telegram.org/file/bot" + props.telegram().token() + "/" + path;
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body();
            }
            throw new IllegalStateException("HTTP " + resp.statusCode());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}


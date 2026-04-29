package com.moneyfirewall.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class ReceiptRecognitionService {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ReceiptRecognitionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public Result recognize(byte[] imageBytes) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("No GEMINI_API_KEY");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Empty image");
        }

        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String prompt = """
                Ты ассистент для распознавания чеков.
                Из изображения чека извлеки позиции и цены.
                Верни только текст в формате:
                - <наименование> — <цена>
                ...
                И в конце отдельной строкой: Итого — <сумма>
                Если что-то не читается, пропусти строку.
                """;

        try {
            String body = """
                    {
                      "contents": [
                        {
                          "parts": [
                            { "text": %s },
                            {
                              "inline_data": {
                                "mime_type": "image/jpeg",
                                "data": "%s"
                              }
                            }
                          ]
                        }
                      ],
                      "generationConfig": {
                        "temperature": 0.2
                      }
                    }
                    """.formatted(objectMapper.writeValueAsString(prompt), base64);

            URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String text = root.at("/candidates/0/content/parts/0/text").asText(null);
            if (text == null || text.isBlank()) {
                text = "Не удалось распознать";
            }
            return new Result(text.trim());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record Result(String text) {}
}


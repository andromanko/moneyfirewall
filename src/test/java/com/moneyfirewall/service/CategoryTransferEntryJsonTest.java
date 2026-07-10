package com.moneyfirewall.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CategoryTransferEntryJsonTest {
    @Test
    void roundTripsThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<CategoryService.CategoryTransferEntry> entries = List.of(
                new CategoryService.CategoryTransferEntry("EXPENSE", "Продукты", List.of("Магазины", "Кафе")),
                new CategoryService.CategoryTransferEntry("INCOME", "Зарплата", List.of())
        );

        byte[] json = mapper.writeValueAsBytes(entries);
        List<CategoryService.CategoryTransferEntry> parsed = mapper.readValue(
                json, new TypeReference<List<CategoryService.CategoryTransferEntry>>() {
                });

        Assertions.assertEquals(entries, parsed);
        Assertions.assertTrue(new String(json, java.nio.charset.StandardCharsets.UTF_8).contains("\"children\""));
    }

    @Test
    void parsesExportShapeFromRawJson() throws Exception {
        String raw = """
                [
                  {"kind":"EXPENSE","name":"Продукты","children":["Магазины","Кафе"]},
                  {"kind":"INCOME","name":"Зарплата","children":[]}
                ]
                """;
        ObjectMapper mapper = new ObjectMapper();
        List<CategoryService.CategoryTransferEntry> parsed = mapper.readValue(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new TypeReference<List<CategoryService.CategoryTransferEntry>>() {
                });

        Assertions.assertEquals(2, parsed.size());
        Assertions.assertEquals("Продукты", parsed.get(0).name());
        Assertions.assertEquals(List.of("Магазины", "Кафе"), parsed.get(0).children());
    }
}

package com.moneyfirewall.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CategoryRuleTransferEntryJsonTest {
    @Test
    void roundTripsThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<CategoryRuleService.CategoryRuleTransferEntry> entries = List.of(
                new CategoryRuleService.CategoryRuleTransferEntry(
                        "EXPENSE", "Продукты / Магазины", "евроопт", false, 100, null, null, null, false),
                new CategoryRuleService.CategoryRuleTransferEntry(
                        "INCOME", "Зарплата", "", false, 50, "Alfa", new BigDecimal("4000"), null, true)
        );

        byte[] json = mapper.writeValueAsBytes(entries);
        List<CategoryRuleService.CategoryRuleTransferEntry> parsed = mapper.readValue(
                json, new TypeReference<List<CategoryRuleService.CategoryRuleTransferEntry>>() {
                });

        Assertions.assertEquals(entries, parsed);
    }

    @Test
    void combinedBundleShapeParsesIntoBothLists() throws Exception {
        // Mirrors MoneyFirewallUpdateConsumer's private CategoryBundleExport(categories, rules) record.
        String raw = """
                {
                  "categories": [
                    {"kind":"EXPENSE","name":"Продукты","children":["Магазины","Кафе"]}
                  ],
                  "rules": [
                    {"kind":"EXPENSE","category":"Продукты / Магазины","pattern":"евроопт","isRegex":false,"priority":100,"accountName":null,"minAmount":null,"exactAmount":null,"oncePerMonth":false}
                  ]
                }
                """;
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> asMap = mapper.readValue(raw.getBytes(StandardCharsets.UTF_8), Map.class);
        Assertions.assertTrue(asMap.containsKey("categories"));
        Assertions.assertTrue(asMap.containsKey("rules"));

        List<CategoryService.CategoryTransferEntry> categories = mapper.convertValue(
                asMap.get("categories"), new TypeReference<List<CategoryService.CategoryTransferEntry>>() {
                });
        List<CategoryRuleService.CategoryRuleTransferEntry> rules = mapper.convertValue(
                asMap.get("rules"), new TypeReference<List<CategoryRuleService.CategoryRuleTransferEntry>>() {
                });

        Assertions.assertEquals(1, categories.size());
        Assertions.assertEquals("Продукты", categories.get(0).name());
        Assertions.assertEquals(1, rules.size());
        Assertions.assertEquals("Продукты / Магазины", rules.get(0).category());
        Assertions.assertEquals("евроопт", rules.get(0).pattern());
    }
}

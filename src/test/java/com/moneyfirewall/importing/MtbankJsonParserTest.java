package com.moneyfirewall.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyfirewall.domain.ImportFileType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MtbankJsonParserTest {
    private final MtbankJsonStatementParser parser = new MtbankJsonStatementParser(new ObjectMapper());

    @Test
    void supportsOnlyMtbankJson() {
        assertTrue(parser.supports("mtbank", ImportFileType.JSON));
        assertTrue(parser.supports("MTBank", ImportFileType.JSON));
        assertFalse(parser.supports("mtbank", ImportFileType.PDF));
        assertFalse(parser.supports("generic", ImportFileType.JSON));
    }

    @Test
    void parsesNestedDataWithOperationsAndSkipsFailedOnes() throws Exception {
        String json = """
                {
                  "success": true,
                  "data": [ {
                    "accountCurr": "EUR",
                    "accountId": "BY76MTBK30140008999902473710",
                    "operations": [ {
                      "amount": "700.00",
                      "curr": "EUR",
                      "debitFlag": "0",
                      "description": "Внесение наличных",
                      "error": "Неверный PIN",
                      "operationDate": "2026-05-30",
                      "place": "MTB RKC 42",
                      "status": "E",
                      "transDate": "2026-05-30 10:09:44"
                    }, {
                      "amount": "700.00",
                      "curr": "EUR",
                      "debitFlag": "1",
                      "description": "Пополнение наличными в ПВН МТБанка",
                      "error": null,
                      "operationDate": "2026-06-01",
                      "place": "MTB RKC 42",
                      "status": "T",
                      "transDate": "2026-05-30 10:09:48"
                    }, {
                      "amount": "252.79",
                      "curr": "EUR",
                      "debitFlag": "0",
                      "description": "Оплата товаров и услуг",
                      "error": null,
                      "operationDate": "2026-06-04",
                      "place": "RYANAIR224N6EV7K",
                      "status": "T",
                      "transDate": "2026-05-30 11:41:19"
                    } ]
                  } ]
                }
                """;
        List<ParsedOperation> ops = parser.parse(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, ops.size(), "the failed PIN-error operation must be skipped");

        ParsedOperation topUp = ops.get(0);
        assertEquals("INCOME", topUp.direction());
        assertEquals("EUR", topUp.currency());
        assertEquals("700.00", topUp.amount().toPlainString());
        assertEquals("MTB RKC 42 - Пополнение наличными в ПВН МТБанка", topUp.counterpartyRaw());
        assertEquals("MTBank", topUp.accountName());

        ParsedOperation payment = ops.get(1);
        assertEquals("EXPENSE", payment.direction());
        assertEquals("252.79", payment.amount().toPlainString());
        assertEquals("RYANAIR224N6EV7K - Оплата товаров и услуг", payment.counterpartyRaw());
    }

    @Test
    void parsesFlatOperationsAtTopLevel() throws Exception {
        String json = """
                {
                  "operations": [ {
                    "amount": "10.50",
                    "curr": "BYN",
                    "debitFlag": "0",
                    "description": "Оплата товаров и услуг",
                    "place": "EUROOPT",
                    "status": "T",
                    "operationDate": "2026-06-10"
                  } ]
                }
                """;
        List<ParsedOperation> ops = parser.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, ops.size());
        assertEquals("EXPENSE", ops.getFirst().direction());
        assertEquals("EUROOPT - Оплата товаров и услуг", ops.getFirst().counterpartyRaw());
        assertEquals("BYN", ops.getFirst().currency());
    }

    @Test
    void parsesPlainArrayOfOperations() throws Exception {
        String json = """
                [ {
                  "amount": "50.00",
                  "curr": "USD",
                  "debitFlag": "1",
                  "description": "Пополнение",
                  "place": null,
                  "status": "T",
                  "operationDate": "2026-06-11"
                } ]
                """;
        List<ParsedOperation> ops = parser.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, ops.size());
        assertEquals("INCOME", ops.getFirst().direction());
        assertEquals("USD", ops.getFirst().currency());
        assertEquals("Пополнение", ops.getFirst().counterpartyRaw());
    }

    @Test
    void throwsOnUnrecognizedShape() {
        String json = "{\"foo\": \"bar\"}";
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("Unsupported"));
    }
}

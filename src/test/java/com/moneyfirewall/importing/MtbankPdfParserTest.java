package com.moneyfirewall.importing;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MtbankPdfParserTest {
    @Test
    void parseMtbankMerchantLine() throws Exception {
        String text = """
                ЗАО «МТБанк»
                Выписка по счету
                T 26.04.2026
                14:32:15 26.04.2026
                MAGAZIN EVROOPT
                Оплата товаров/услуг
                BYN
                12.34
                1000.00
                -
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("MAGAZIN EVROOPT", ops.getFirst().counterpartyRaw());
        assertFalse("Imported".equals(ops.getFirst().counterpartyRaw()));
    }

    @Test
    void parseMtbankCardAndMerchant() throws Exception {
        String text = """
                ЗАО «МТБанк»
                Выписка по счету
                T 01.05.2026
                10:11:22 01.05.2026
                5355**1234 MAGAZIN GROSHYK
                Оплата товаров/услуг
                BYN
                5.00
                500.00
                -
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("MAGAZIN GROSHYK", ops.getFirst().counterpartyRaw());
    }

    @Test
    void parseMtbankMp2pIsExpenseEvenWithPlusSign() throws Exception {
        String text = """
                ЗАО «МТБанк»
                Выписка по счету
                T 28.05.2026
                11:17:37 28.05.2026
                MP2P 140103
                BYN
                200.00
                1000.00
                +
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("EXPENSE", ops.getFirst().direction());
        assertEquals("MP2P 140103", ops.getFirst().counterpartyRaw());
    }

    @Test
    void parseMtbankGluedCardMerchant() throws Exception {
        String text = """
                ЗАО «МТБанк»
                Выписка по счету
                T 28.05.2026
                08:18:29 28.05.2026
                554832******8779MTBANK MINSK BY
                BYN
                10.00
                1000.00
                -
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("MTBANK MINSK BY", ops.getFirst().counterpartyRaw());
    }

    @Test
    void parseMtbankCounterpartyFromDescription() throws Exception {
        String text = """
                ЗАО «МТБанк»
                Выписка по счету
                T 02.05.2026
                11:00:00 02.05.2026
                Оплата товаров/услуг Общество с ограниченной ответственностью Santa
                BYN
                10.00
                490.00
                -
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertTrue(ops.getFirst().counterpartyRaw().contains("Santa"));
        assertFalse("Imported".equals(ops.getFirst().counterpartyRaw()));
    }

    @Test
    void parseMtbankEurTransaction() throws Exception {
        String text = """
                ЗАО «МТБанк»
                Выписка по счету
                T 03.06.2026
                12:00:00 03.06.2026
                SHOP EUROPE
                Оплата товаров/услуг
                EUR
                15.50
                1000.00
                -
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("EUR", ops.getFirst().currency());
        assertEquals("15.50", ops.getFirst().amount().toPlainString());
        assertEquals("EXPENSE", ops.getFirst().direction());
    }
}

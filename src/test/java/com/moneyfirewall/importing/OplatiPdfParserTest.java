package com.moneyfirewall.importing;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OplatiPdfParserTest {
    // Mirrors the actual PDFBox text extraction of a real Белинвестбанк/OPLATI e-wallet statement:
    // the transaction id and the first word of "Тип платежа" land glued on one line, and
    // "Тип платежа"/"Детали операции" words are wrapped across several short lines.
    private static final String HEADER = """
            ОАО "Белинвестбанк"
            Выписка по операциям электронного кошелька BY37BLBB00000000000000866225
            """;

    @Test
    void parseOplatiStatement() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/oplati-statement-2026-05.pdf")) {
            SimplePdfStatementParser parser = new SimplePdfStatementParser();
            var ops = parser.parse(in.readAllBytes());
            assertTrue(ops.size() >= 40, "expected OPLATI transactions, got " + ops.size());
        }
    }

    @Test
    void goodsPaymentIsExpense() throws Exception {
        String text = HEADER + """
                02.01.2026
                18:25
                496316234 Оплата
                товаров
                /услуг
                10.66 460.72 BYN Оплата товаров/услуг Общество с ограниченной ответственностью
                "ЛИБРЕТИК"
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("EXPENSE", ops.getFirst().direction());
        assertEquals("10.66", ops.getFirst().amount().toPlainString());
        assertTrue(ops.getFirst().counterpartyRaw().contains("ЛИБРЕТИК"));
    }

    @Test
    void walletTopUpIsIncome() throws Exception {
        String text = HEADER + """
                06.01.2026
                10:16
                497626232 Пополне
                ние
                кошельк
                а
                8.53 367.16 BYN Пополнение электронного кошелька с текущего счета
                BY07BLBB30141200000006565027, Манибэк 8.53 BYN
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("INCOME", ops.getFirst().direction());
        assertEquals("8.53", ops.getFirst().amount().toPlainString());
    }

    @Test
    void eripTopUpOfAnotherCardIsExpenseNotIncome() throws Exception {
        // The counterparty text literally contains "Пополнение дебетовой карты" (topping up a
        // DIFFERENT account), which must not be read as this wallet receiving money — the wallet's
        // own balance drops by the paid amount, so this is an outgoing ЕРИП payment (EXPENSE).
        String text = HEADER + """
                06.01.2026
                11:27
                497650478 Оплата
                в ЕРИП
                300 67.16 BYN Платежи в ЕРИП Пополнение дебетовой карты:33124404 4325151
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("EXPENSE", ops.getFirst().direction());
        assertEquals("300", ops.getFirst().amount().toPlainString());
        assertTrue(ops.getFirst().counterpartyRaw().contains("33124404"),
                "must keep the card number so transfer-linking can pair it with the other bank's leg");
    }

    @Test
    void eripPlainPaymentIsExpense() throws Exception {
        String text = HEADER + """
                11.01.2026
                20:53
                500005265 Оплата
                в ЕРИП
                4.1 0.00 BYN Платежи в ЕРИП 4485471
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("EXPENSE", ops.getFirst().direction());
    }

    @Test
    void virtualCardPaymentIsExpense() throws Exception {
        String text = HEADER + """
                21.01.2026
                18:35
                504990621 Оплата
                по
                виртуал
                ьной
                картой
                82.25 165.13 BYN Оплата товаров и услуг по виртуальной карте I.-SHOP "WILDBERRIES.B
                >MINSK BY
                """;
        List<ParsedOperation> ops = new SimplePdfStatementParser().parseExtractedText(text);
        assertEquals(1, ops.size());
        assertEquals("EXPENSE", ops.getFirst().direction());
    }
}

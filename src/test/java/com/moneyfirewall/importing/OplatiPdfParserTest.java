package com.moneyfirewall.importing;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OplatiPdfParserTest {
    @Test
    void parseOplatiStatement() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/oplati-statement-2026-05.pdf")) {
            SimplePdfStatementParser parser = new SimplePdfStatementParser();
            var ops = parser.parse(in.readAllBytes());
            assertTrue(ops.size() >= 40, "expected OPLATI transactions, got " + ops.size());
        }
    }
}

package com.moneyfirewall.service;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualAmountEntryTest {
    @Test
    void amountOnlyDefaultsToByn() {
        ManualAmountEntry e = parse("40");
        assertEquals(0, new BigDecimal("40").compareTo(e.amount()));
        assertEquals("BYN", e.currency());
        assertNull(e.comment());
    }

    @Test
    void separatedCurrencyCodeAndComment() {
        ManualAmountEntry e = parse("100 USD обед в городе");
        assertEquals(0, new BigDecimal("100").compareTo(e.amount()));
        assertEquals("USD", e.currency());
        assertEquals("обед в городе", e.comment());
    }

    @Test
    void commaDecimalSeparatorAndCommentWithoutCurrency() {
        ManualAmountEntry e = parse("25,50 продукты");
        assertEquals(0, new BigDecimal("25.50").compareTo(e.amount()));
        assertEquals("BYN", e.currency());
        assertEquals("продукты", e.comment());
    }

    @Test
    void symbolGluedToAmount() {
        ManualAmountEntry e = parse("20$ такси");
        assertEquals(0, new BigDecimal("20").compareTo(e.amount()));
        assertEquals("USD", e.currency());
        assertEquals("такси", e.comment());
    }

    @Test
    void leadingSymbol() {
        ManualAmountEntry e = parse("€15 кофе");
        assertEquals(0, new BigDecimal("15").compareTo(e.amount()));
        assertEquals("EUR", e.currency());
        assertEquals("кофе", e.comment());
    }

    /** The reason currency codes come from a fixed list: a 3-letter word must stay a comment. */
    @Test
    void threeLetterCommentIsNotMistakenForACurrency() {
        ManualAmountEntry e = parse("100 еда");
        assertEquals("BYN", e.currency());
        assertEquals("еда", e.comment());
    }

    @Test
    void rurIsNormalisedToRub() {
        assertEquals("RUB", parse("500 RUR перевод").currency());
    }

    @Test
    void lowercaseCodeIsAccepted() {
        assertEquals("USD", parse("10 usd подарок").currency());
    }

    @Test
    void rejectsNonNumericZeroAndBlank() {
        assertTrue(ManualAmountEntry.parse("привет").isEmpty());
        assertTrue(ManualAmountEntry.parse("0").isEmpty());
        assertTrue(ManualAmountEntry.parse("   ").isEmpty());
        assertTrue(ManualAmountEntry.parse(null).isEmpty());
    }

    private ManualAmountEntry parse(String text) {
        Optional<ManualAmountEntry> parsed = ManualAmountEntry.parse(text);
        assertTrue(parsed.isPresent(), "expected to parse: " + text);
        return parsed.get();
    }
}

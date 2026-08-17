package com.moneyfirewall.service;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceHashtagTest {
    @Test
    void extractsDistinctLowercasedHashtagsFromFreeText() {
        Set<String> tags = ReportService.extractHashtags("#Корпоратив подарок коллеге #корпоратив #Друзья");
        assertEquals(Set.of("#корпоратив", "#друзья"), tags);
    }

    @Test
    void plainCommentWithoutHashSignYieldsNothing() {
        assertTrue(ReportService.extractHashtags("просто комментарий без тега").isEmpty());
    }

    @Test
    void blankOrNullYieldsNothing() {
        assertTrue(ReportService.extractHashtags(null).isEmpty());
        assertTrue(ReportService.extractHashtags("   ").isEmpty());
    }

    @Test
    void hashtagSheetHasOneRowPerTagWithMonthColumnsAndTotal() {
        List<YearMonth> months = List.of(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        Set<String> tags = new TreeSet<>(Set.of("#корпоратив", "#друзья"));

        List<List<Object>> rows = ReportService.buildHashtagSheet(months, tags);

        assertEquals(3, rows.size(), "header + 2 tag rows");
        assertEquals(List.of("Хэштег", "Январь 2026", "Февраль 2026", "Итого"), rows.get(0));
        assertEquals("#друзья", rows.get(1).get(0));
        assertEquals("#корпоратив", rows.get(2).get(0));
        // Per-month cells and the total are live formulas, not Java-computed numbers.
        assertTrue(rows.get(1).get(1) instanceof com.moneyfirewall.reporting.FormulaCell);
        assertTrue(rows.get(1).get(3) instanceof com.moneyfirewall.reporting.FormulaCell);
    }

    @Test
    void emptyHashtagSetStillProducesJustTheHeaderRow() {
        List<List<Object>> rows = ReportService.buildHashtagSheet(List.of(YearMonth.of(2026, 1)), Set.of());
        assertEquals(1, rows.size(), "header only, matching ByCategory's always-present-header convention");
        assertEquals("Хэштег", rows.get(0).get(0));
    }
}

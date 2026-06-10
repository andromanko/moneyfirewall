package com.moneyfirewall.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryRuleConditionsTest {
    @Test
    void parseSalaryRule() {
        CategoryRuleConditions c = CategoryRuleConditions.fromMatchText("account:Alfa min:4000 prio:10");
        assertEquals("Alfa", c.accountName());
        assertEquals(new BigDecimal("4000"), c.minAmount());
        assertEquals(10, c.priority());
        assertTrue(c.hasConstraints());
    }

    @Test
    void parseMonthlyRule() {
        CategoryRuleConditions c = CategoryRuleConditions.fromMatchText("account:Alfa amount:500 once_month prio:20");
        assertEquals("Alfa", c.accountName());
        assertEquals(new BigDecimal("500"), c.exactAmount());
        assertTrue(c.oncePerMonth());
        assertEquals(20, c.priority());
    }
}

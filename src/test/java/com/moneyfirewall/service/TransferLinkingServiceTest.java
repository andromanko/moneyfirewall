package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.Transaction;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * findBestMatch has no repository dependency, so these exercise it directly against
 * hand-built (unpersisted) Transaction/Account entities.
 */
class TransferLinkingServiceTest {
    private final TransferLinkingService service = new TransferLinkingService(null, null, null);

    @Test
    void matchesSameAccountPairWithinTightWindow() {
        Account mtbank = account();
        Instant t0 = Instant.parse("2026-07-04T20:32:41Z");
        Transaction out = tx(mtbank, "700", t0);
        Transaction in = tx(mtbank, "700", t0.plusSeconds(2));

        Transaction match = service.findBestMatch(out, List.of(in), Duration.ofHours(48), false);

        assertSame(in, match);
    }

    @Test
    void rejectsSameAccountPairBeyondTightWindow() {
        Account mtbank = account();
        Instant t0 = Instant.parse("2026-07-04T20:32:41Z");
        Transaction out = tx(mtbank, "700", t0);
        Transaction in = tx(mtbank, "700", t0.plus(Duration.ofMinutes(5)));

        Transaction match = service.findBestMatch(out, List.of(in), Duration.ofHours(48), false);

        assertNull(match, "same-account matches beyond the tight window are too likely to be coincidental");
    }

    @Test
    void stillMatchesCrossAccountPairWithinTheWiderWindow() {
        Account mtbank = account();
        Account alfa = account();
        Instant t0 = Instant.parse("2026-07-04T20:32:41Z");
        Transaction out = tx(mtbank, "700", t0);
        Transaction in = tx(alfa, "700", t0.plus(Duration.ofHours(1)));

        Transaction match = service.findBestMatch(out, List.of(in), Duration.ofHours(48), false);

        assertNotNull(match);
    }

    private Account account() {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        return a;
    }

    private Transaction tx(Account account, String amount, Instant occurredAt) {
        Transaction t = new Transaction();
        t.setAccount(account);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency("BYN");
        t.setOccurredAt(occurredAt);
        return t;
    }
}

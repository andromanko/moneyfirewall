package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.domain.TransferGroup;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.repo.TransferGroupRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MTBank tags both sides of a P2P transfer with "MP2P", so the marker cannot decide direction on
 * its own: the receiving leg reads "Пополнение счета &lt;account&gt;", and the statement's own
 * debitFlag (which the importer honours) already had it right.
 */
class TransferLinkingMp2pTest {
    private static final UUID BUDGET_ID = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-04-10T13:44:17Z");
    private static final String MTBANK_INCOMING =
            "MP2P 140103 Пополнение счета BY82MTBK30140008999901211050 за 10.04.2026";

    /**
     * The bug: with no counterpart to pair against, treating MP2P as inherently outgoing rewrote a
     * genuine income into an expense and left it that way — silently inflating Расход.
     */
    @Test
    void loneIncomingMp2pTopUpIsNotRewrittenIntoAnExpense() {
        Transaction topUp = tx(account(), TransactionDirection.INCOME, MTBANK_INCOMING);

        autoLink(topUp);

        assertEquals(TransactionDirection.INCOME, topUp.getDirection(),
                "an unpaired incoming MP2P top-up must keep the direction the statement gave it");
    }

    /** The case the heuristic exists for: MP2P with no account-credit wording is still outgoing. */
    @Test
    void loneMp2pWithoutAccountCreditWordingIsStillTreatedAsOutgoing() {
        Transaction misreported = tx(account(), TransactionDirection.INCOME, "MP2P 140103 Perevod");

        autoLink(misreported);

        assertEquals(TransactionDirection.EXPENSE, misreported.getDirection());
    }

    /** The Alfa→MTBank pair as the statements actually record it: expense + income, and it links. */
    @Test
    void alfaToMtbankPairLinks() {
        Transaction sender = tx(account(), TransactionDirection.EXPENSE, "Popolnenie debetovoj karti: 33124404");
        Transaction receiver = tx(account(), TransactionDirection.INCOME, MTBANK_INCOMING);

        int linked = autoLink(sender, receiver);

        assertEquals(1, linked);
        assertEquals(TransactionDirection.TRANSFER, sender.getDirection());
        assertEquals(TransactionDirection.TRANSFER, receiver.getDirection());
        assertSame(sender.getTransferGroup(), receiver.getTransferGroup());
    }

    /** Both legs stored as expenses (direction already lost) still gets recovered by a rescan. */
    @Test
    void pairWithBothLegsStoredAsExpensesStillGetsLinked() {
        Transaction sender = tx(account(), TransactionDirection.EXPENSE, "Popolnenie debetovoj karti: 33124404");
        Transaction receiver = tx(account(), TransactionDirection.EXPENSE, MTBANK_INCOMING);

        int linked = autoLink(sender, receiver);

        assertEquals(1, linked);
        assertEquals(TransactionDirection.TRANSFER, sender.getDirection());
        assertEquals(TransactionDirection.TRANSFER, receiver.getDirection());
        assertSame(sender.getTransferGroup(), receiver.getTransferGroup());
    }

    private int autoLink(Transaction... candidates) {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        // Mutable: the service sorts the returned list in place.
        when(transactionRepository.findCandidatesForTransferLinking(eq(BUDGET_ID), anyList(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(candidates)));

        TransferGroupRepository transferGroupRepository = mock(TransferGroupRepository.class);
        when(transferGroupRepository.save(any(TransferGroup.class))).thenAnswer(inv -> {
            TransferGroup g = inv.getArgument(0);
            g.setId(UUID.randomUUID());
            return g;
        });

        BudgetRepository budgetRepository = mock(BudgetRepository.class);
        when(budgetRepository.findById(BUDGET_ID)).thenReturn(Optional.of(new Budget()));

        return new TransferLinkingService(transactionRepository, transferGroupRepository, budgetRepository)
                .autoLink(BUDGET_ID, Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-05-01T00:00:00Z"),
                        java.time.Duration.ofHours(48));
    }

    private Account account() {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        return a;
    }

    private Transaction tx(Account account, TransactionDirection direction, String counterparty) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAccount(account);
        t.setDirection(direction);
        t.setAmount(new BigDecimal("350.00"));
        t.setCurrency("BYN");
        t.setOccurredAt(WHEN);
        t.setCounterpartyRaw(counterparty);
        return t;
    }
}

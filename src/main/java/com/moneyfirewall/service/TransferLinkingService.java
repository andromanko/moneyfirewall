package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.domain.TransferGroup;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.repo.TransferGroupRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferLinkingService {
    private static final Pattern IBAN_LIKE = Pattern.compile("BY[0-9A-Z]{10,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONG_ALNUM = Pattern.compile("[0-9A-Za-z]{8,}");
    /** Same amount/currency, different account, posted this close together is a transfer even
     * when the two banks' free-text descriptions share no common token (e.g. "MTB MOBILE BANK"
     * vs "Поступление средств") — coincidental same-amount unrelated transactions this close in
     * time are negligibly likely. */
    private static final Duration TIGHT_WINDOW = Duration.ofMinutes(2);
    /** Same-account expense/income pairs of equal amount posted this close together are the same
     * underlying event recorded as two legs (e.g. an MTBank "MTB MOBILE BANK" internal move), not
     * a coincidence — but capped tighter than the cross-account window since two genuinely
     * unrelated equal-amount transactions on one account are far more plausible than across
     * different accounts. */
    private static final Duration SAME_ACCOUNT_WINDOW = Duration.ofMinutes(1);

    private final TransactionRepository transactionRepository;
    private final TransferGroupRepository transferGroupRepository;
    private final BudgetRepository budgetRepository;

    public TransferLinkingService(
            TransactionRepository transactionRepository,
            TransferGroupRepository transferGroupRepository,
            BudgetRepository budgetRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.transferGroupRepository = transferGroupRepository;
        this.budgetRepository = budgetRepository;
    }

    @Transactional
    public int autoLink(UUID budgetId, Duration window) {
        Instant to = Instant.now();
        Instant from = to.minus(window);
        return autoLinkInOccurredRange(budgetId, from, to, window, false);
    }

    @Transactional
    public int autoLinkAfterImport(UUID budgetId, Instant opsMinOccurredAt, Instant opsMaxOccurredAt) {
        Duration pad = Duration.ofMinutes(10);
        Instant from = opsMinOccurredAt.minus(pad);
        Instant to = opsMaxOccurredAt.plus(pad);
        return autoLinkInOccurredRange(budgetId, from, to, Duration.ofMinutes(10), true);
    }

    @Transactional
    public UUID linkManual(UUID budgetId, UUID outTxId, UUID inTxId) {
        Transaction out = transactionRepository.findById(outTxId).orElseThrow();
        Transaction in = transactionRepository.findById(inTxId).orElseThrow();
        linkPair(budgetId, out, in);
        return out.getTransferGroup().getId();
    }

    @Transactional
    public int unlinkByGroupId(UUID groupId) {
        List<Transaction> all = new ArrayList<>(transactionRepository.findAllByTransferGroupId(groupId));
        all.sort(Comparator.comparing(Transaction::getCreatedAt));
        for (Transaction t : all) {
            t.setTransferGroup(null);
            if (t.getDirection() == TransactionDirection.TRANSFER) {
                t.setDirection(TransactionDirection.EXPENSE);
            }
        }
        if (all.size() == 2) {
            all.get(1).setDirection(TransactionDirection.INCOME);
        }
        transactionRepository.saveAll(all);
        return all.size();
    }

    private int autoLinkInOccurredRange(UUID budgetId, Instant from, Instant to, Duration maxPairDelta, boolean requireSummaryHook) {
        List<Transaction> candidates = transactionRepository.findCandidatesForTransferLinking(
                budgetId,
                List.of(TransactionDirection.INCOME, TransactionDirection.EXPENSE),
                from,
                to
        );
        candidates.sort(Comparator.comparing(Transaction::getOccurredAt));
        fixMisclassifiedTransferLegs(candidates);

        List<Transaction> expenses = candidates.stream().filter(t -> t.getDirection() == TransactionDirection.EXPENSE).toList();
        List<Transaction> incomes = candidates.stream().filter(t -> t.getDirection() == TransactionDirection.INCOME).toList();

        int linked = 0;
        for (Transaction out : expenses) {
            if (out.getTransferGroup() != null) {
                continue;
            }
            Transaction in = findBestMatch(out, incomes, maxPairDelta, requireSummaryHook);
            if (in == null) {
                continue;
            }
            linkPair(budgetId, out, in);
            linked++;
        }
        return linked;
    }

    Transaction findBestMatch(Transaction out, List<Transaction> incomes, Duration maxDelta, boolean requireSummary) {
        Transaction best = null;
        Duration bestAbs = null;
        for (Transaction in : incomes) {
            if (in.getTransferGroup() != null) {
                continue;
            }
            if (!in.getCurrency().equalsIgnoreCase(out.getCurrency())) {
                continue;
            }
            if (in.getAmount().compareTo(out.getAmount()) != 0) {
                continue;
            }
            Duration d = Duration.between(out.getOccurredAt(), in.getOccurredAt()).abs();
            boolean sameAccount = in.getAccount().getId().equals(out.getAccount().getId());
            if (sameAccount) {
                if (d.compareTo(SAME_ACCOUNT_WINDOW) > 0) {
                    continue;
                }
            } else {
                if (d.compareTo(maxDelta) > 0) {
                    continue;
                }
                if (requireSummary && d.compareTo(TIGHT_WINDOW) > 0 && !summariesHookCompatible(out, in)) {
                    continue;
                }
            }
            if (best == null || d.compareTo(bestAbs) < 0) {
                best = in;
                bestAbs = d;
            }
        }
        return best;
    }

    private boolean summariesHookCompatible(Transaction a, Transaction b) {
        String sa = summaryText(a);
        String sb = summaryText(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return false;
        }
        if (!intersection(IBAN_LIKE, sa, sb).isEmpty()) {
            return true;
        }
        if (!intersection(LONG_ALNUM, sa, sb).isEmpty()) {
            return true;
        }
        return walletTransferHeuristic(sa, sb);
    }

    private String summaryText(Transaction t) {
        String cp = t.getCounterpartyRaw() == null ? "" : t.getCounterpartyRaw();
        String d = t.getDescription() == null ? "" : t.getDescription();
        return (cp + " " + d).toLowerCase(Locale.ROOT);
    }

    private Set<String> intersection(Pattern p, String a, String b) {
        Set<String> sa = tokens(p, a);
        Set<String> sb = tokens(p, b);
        Set<String> r = new HashSet<>(sa);
        r.retainAll(sb);
        return r;
    }

    private Set<String> tokens(Pattern p, String s) {
        Set<String> out = new HashSet<>();
        Matcher m = p.matcher(s);
        while (m.find()) {
            out.add(m.group().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private boolean walletTransferHeuristic(String sa, String sb) {
        boolean aTopup = topupLike(sa);
        boolean bTopup = topupLike(sb);
        boolean aOut = outLike(sa);
        boolean bOut = outLike(sb);
        return (aTopup && bOut) || (bTopup && aOut);
    }

    private boolean topupLike(String s) {
        return s.contains("пополн")
                || s.contains("popoln")
                || s.contains("кошельк")
                || s.contains("кошелёк")
                || s.contains("кошелек")
                || s.contains("debetovoj karti");
    }

    private void fixMisclassifiedTransferLegs(List<Transaction> candidates) {
        for (Transaction t : candidates) {
            if (t.getTransferGroup() != null) {
                continue;
            }
            if (t.getDirection() != TransactionDirection.INCOME) {
                continue;
            }
            if (isOutgoingTransferLike(t)) {
                t.setDirection(TransactionDirection.EXPENSE);
                t.setCategory(null);
                transactionRepository.save(t);
            }
        }
        fixTopupLegsRecordedAsExpense(candidates);
    }

    /**
     * Some banks (observed: Alfa card top-ups, e.g. "Popolnenie debetovoj karti") record the
     * *receiving* leg of a transfer as an expense in their own export even though it's
     * semantically incoming money. Such a leg — topup-worded but without the mp2p/mp2b sender
     * marker — gets reclassified to INCOME when a same-amount, same-currency, different-account
     * expense that IS sender-worded (mp2p/mp2b) posted within the tight window, so the normal
     * expense/income pairing below can link them.
     */
    private void fixTopupLegsRecordedAsExpense(List<Transaction> candidates) {
        List<Transaction> expenses = candidates.stream()
                .filter(t -> t.getDirection() == TransactionDirection.EXPENSE && t.getTransferGroup() == null)
                .toList();
        for (Transaction t : expenses) {
            if (isOutgoingTransferLike(t) || !topupLike(summaryText(t))) {
                continue;
            }
            boolean hasSendingCounterpart = expenses.stream().anyMatch(other ->
                    !other.getId().equals(t.getId())
                            && isOutgoingTransferLike(other)
                            && other.getCurrency().equalsIgnoreCase(t.getCurrency())
                            && other.getAmount().compareTo(t.getAmount()) == 0
                            && !other.getAccount().getId().equals(t.getAccount().getId())
                            && Duration.between(t.getOccurredAt(), other.getOccurredAt()).abs().compareTo(TIGHT_WINDOW) <= 0);
            if (hasSendingCounterpart) {
                t.setDirection(TransactionDirection.INCOME);
                t.setCategory(null);
                transactionRepository.save(t);
            }
        }
    }

    private boolean isOutgoingTransferLike(Transaction t) {
        String s = summaryText(t);
        return s.contains("mp2p") || s.contains("mp2b");
    }

    private boolean outLike(String s) {
        return s.contains("перевод")
                || s.contains("mp2p")
                || s.contains("списан")
                || s.contains("интернет-банк")
                || s.contains("popolnenie")
                || s.contains("popoln");
    }

    private void linkPair(UUID budgetId, Transaction out, Transaction in) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        TransferGroup group = new TransferGroup();
        group.setBudget(budget);
        group.setCreatedAt(Instant.now());
        TransferGroup saved = transferGroupRepository.save(group);

        out.setTransferGroup(saved);
        in.setTransferGroup(saved);
        out.setCategory(null);
        in.setCategory(null);
        out.setDirection(TransactionDirection.TRANSFER);
        in.setDirection(TransactionDirection.TRANSFER);

        transactionRepository.save(out);
        transactionRepository.save(in);
    }
}

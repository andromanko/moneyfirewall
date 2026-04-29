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
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferLinkingService {
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
        List<Transaction> candidates = transactionRepository.findCandidatesForTransferLinking(
                budgetId,
                List.of(TransactionDirection.INCOME, TransactionDirection.EXPENSE),
                from,
                to
        );
        candidates.sort(Comparator.comparing(Transaction::getOccurredAt));

        List<Transaction> expenses = candidates.stream().filter(t -> t.getDirection() == TransactionDirection.EXPENSE).toList();
        List<Transaction> incomes = candidates.stream().filter(t -> t.getDirection() == TransactionDirection.INCOME).toList();

        int linked = 0;
        for (Transaction out : expenses) {
            Transaction in = findBestMatch(out, incomes, window);
            if (in == null) {
                continue;
            }
            linkPair(budgetId, out, in);
            linked++;
        }
        return linked;
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

    private Transaction findBestMatch(Transaction out, List<Transaction> incomes, Duration window) {
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
            if (in.getAccount().getId().equals(out.getAccount().getId())) {
                continue;
            }
            Duration d = Duration.between(out.getOccurredAt(), in.getOccurredAt()).abs();
            if (d.compareTo(window) > 0) {
                continue;
            }
            return in;
        }
        return null;
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


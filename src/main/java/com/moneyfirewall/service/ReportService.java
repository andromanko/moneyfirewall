package com.moneyfirewall.service;

import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.reporting.ReportTables;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private final TransactionRepository transactionRepository;

    public ReportService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public ReportTables build(UUID budgetId, Instant from, Instant to) {
        List<Transaction> tx = transactionRepository.findAllInRange(budgetId, from, to);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;

        Map<String, BigDecimal> byCategory = new HashMap<>();
        Map<String, BigDecimal> byMember = new HashMap<>();

        List<List<Object>> txRows = new ArrayList<>();
        txRows.add(List.of("occurredAt", "direction", "amount", "currency", "account", "category", "counterparty", "member", "isTransfer"));

        for (Transaction t : tx) {
            boolean isTransfer = t.getDirection() == TransactionDirection.TRANSFER || t.getTransferGroup() != null;
            String cat = t.getCategory() == null ? "" : t.getCategory().getName();
            String member = t.getUser() == null ? "" : t.getUser().getDisplayName();
            String account = t.getAccount() == null ? "" : t.getAccount().getName();
            String cp = t.getCounterpartyNormalized() == null ? "" : t.getCounterpartyNormalized();

            if (!isTransfer) {
                if (t.getDirection() == TransactionDirection.INCOME) {
                    income = income.add(t.getAmount());
                } else if (t.getDirection() == TransactionDirection.EXPENSE) {
                    expense = expense.add(t.getAmount());
                    if ("Комиссии".equalsIgnoreCase(cat) || "FEE".equalsIgnoreCase(cp)) {
                        fees = fees.add(t.getAmount());
                    }
                    if (!cat.isBlank()) {
                        byCategory.merge(cat, t.getAmount(), BigDecimal::add);
                    }
                    if (!member.isBlank()) {
                        byMember.merge(member, t.getAmount(), BigDecimal::add);
                    }
                }
            }

            txRows.add(List.of(t.getOccurredAt().toString(), t.getDirection().name(), t.getAmount(), t.getCurrency(), account, cat, cp, member, isTransfer));
        }

        List<List<Object>> summary = new ArrayList<>();
        summary.add(List.of("metric", "value"));
        summary.add(List.of("income", income));
        summary.add(List.of("expense", expense));
        summary.add(List.of("net", income.subtract(expense)));
        summary.add(List.of("fees", fees));

        List<List<Object>> catRows = new ArrayList<>();
        catRows.add(List.of("category", "amount"));
        byCategory.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> catRows.add(List.of(e.getKey(), e.getValue())));

        List<List<Object>> memberRows = new ArrayList<>();
        memberRows.add(List.of("member", "expense"));
        byMember.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> memberRows.add(List.of(e.getKey(), e.getValue())));

        return new ReportTables(summary, catRows, memberRows, txRows);
    }
}


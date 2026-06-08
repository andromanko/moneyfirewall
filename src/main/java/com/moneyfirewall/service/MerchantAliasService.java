package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.MerchantAlias;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.MerchantAliasRepository;
import com.moneyfirewall.repo.TransactionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantAliasService {
    private final MerchantAliasRepository aliasRepository;
    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public MerchantAliasService(
            MerchantAliasRepository aliasRepository,
            BudgetRepository budgetRepository,
            TransactionRepository transactionRepository
    ) {
        this.aliasRepository = aliasRepository;
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public String normalize(UUID budgetId, String counterpartyRaw) {
        if (counterpartyRaw == null || counterpartyRaw.isBlank()) {
            return counterpartyRaw;
        }
        List<MerchantAlias> rules = aliasRepository.findAllByBudgetId(budgetId);
        for (MerchantAlias a : rules) {
            if (a.isRegex()) {
                if (Pattern.compile(a.getPattern(), Pattern.CASE_INSENSITIVE).matcher(counterpartyRaw).find()) {
                    return a.getNormalizedName();
                }
            } else {
                if (counterpartyRaw.toLowerCase().contains(a.getPattern().toLowerCase())) {
                    return a.getNormalizedName();
                }
            }
        }
        return counterpartyRaw;
    }

    @Transactional(readOnly = true)
    public String resolveDisplayName(UUID budgetId, String counterpartyRaw, String counterpartyNormalized) {
        if (counterpartyRaw != null && !counterpartyRaw.isBlank()) {
            return normalize(budgetId, counterpartyRaw);
        }
        if (counterpartyNormalized != null && !counterpartyNormalized.isBlank()) {
            return counterpartyNormalized;
        }
        return "";
    }

    @Transactional
    public int reapplyNicknames(UUID budgetId, Instant from, Instant to) {
        List<Transaction> tx = transactionRepository.findAllInRange(budgetId, from, to);
        int updated = 0;
        for (Transaction t : tx) {
            if (t.getDirection() == TransactionDirection.TRANSFER || t.getTransferGroup() != null) {
                continue;
            }
            String raw = t.getCounterpartyRaw();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String nickname = normalize(budgetId, raw);
            if (!nickname.equals(t.getCounterpartyNormalized())) {
                t.setCounterpartyNormalized(nickname);
                updated++;
            }
        }
        return updated;
    }

    @Transactional
    public MerchantAlias add(UUID budgetId, String pattern, String normalizedName, int priority, boolean isRegex) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        MerchantAlias a = new MerchantAlias();
        a.setBudget(budget);
        a.setPattern(pattern);
        a.setNormalizedName(normalizedName);
        a.setPriority(priority);
        a.setRegex(isRegex);
        a.setCreatedAt(Instant.now());
        return aliasRepository.save(a);
    }

    @Transactional(readOnly = true)
    public List<MerchantAlias> list(UUID budgetId) {
        return aliasRepository.findAllByBudgetId(budgetId);
    }

    @Transactional(readOnly = true)
    public Optional<MerchantAlias> find(UUID budgetId, UUID id) {
        return aliasRepository.findByIdAndBudget_Id(id, budgetId);
    }

    @Transactional
    public void delete(UUID aliasId) {
        aliasRepository.deleteById(aliasId);
    }
}


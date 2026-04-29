package com.moneyfirewall.service;

import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.MerchantAlias;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.MerchantAliasRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantAliasService {
    private final MerchantAliasRepository aliasRepository;
    private final BudgetRepository budgetRepository;

    public MerchantAliasService(MerchantAliasRepository aliasRepository, BudgetRepository budgetRepository) {
        this.aliasRepository = aliasRepository;
        this.budgetRepository = budgetRepository;
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

    @Transactional
    public void delete(UUID aliasId) {
        aliasRepository.deleteById(aliasId);
    }
}


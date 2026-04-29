package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.AccountType;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.repo.AccountRepository;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;

    public AccountService(AccountRepository accountRepository, BudgetRepository budgetRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.budgetRepository = budgetRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Account create(UUID budgetId, UUID ownerUserId, String name, AccountType type, String currency) {
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        Account account = new Account();
        account.setBudget(budget);
        account.setOwnerUser(ownerUserId == null ? null : userRepository.findById(ownerUserId).orElseThrow());
        account.setName(name);
        account.setType(type);
        account.setCurrency(currency);
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public List<Account> list(UUID budgetId) {
        return accountRepository.findAllByBudgetId(budgetId);
    }

    @Transactional(readOnly = true)
    public Account getByName(UUID budgetId, String name) {
        return accountRepository.findByBudgetIdAndName(budgetId, name).orElseThrow();
    }

    @Transactional
    public Account rename(UUID budgetId, UUID accountId, String newName) {
        Account a = accountRepository.findById(accountId).orElseThrow();
        if (a.getBudget() == null || a.getBudget().getId() == null || !a.getBudget().getId().equals(budgetId)) {
            throw new IllegalArgumentException("Wrong budget");
        }
        a.setName(newName);
        return accountRepository.save(a);
    }

    @Transactional
    public void delete(UUID budgetId, UUID accountId) {
        Account a = accountRepository.findById(accountId).orElseThrow();
        if (a.getBudget() == null || a.getBudget().getId() == null || !a.getBudget().getId().equals(budgetId)) {
            throw new IllegalArgumentException("Wrong budget");
        }
        accountRepository.delete(a);
    }
}


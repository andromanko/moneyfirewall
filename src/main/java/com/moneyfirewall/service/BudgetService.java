package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.AccountType;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.BudgetMember;
import com.moneyfirewall.domain.BudgetRole;
import com.moneyfirewall.domain.User;
import com.moneyfirewall.domain.UserSettings;
import com.moneyfirewall.repo.AccountRepository;
import com.moneyfirewall.repo.BudgetMemberRepository;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.UserRepository;
import com.moneyfirewall.repo.UserSettingsRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetService {
    private final BudgetRepository budgetRepository;
    private final BudgetMemberRepository budgetMemberRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    public BudgetService(
            BudgetRepository budgetRepository,
            BudgetMemberRepository budgetMemberRepository,
            UserSettingsRepository userSettingsRepository,
            UserRepository userRepository,
            AccountRepository accountRepository
    ) {
        this.budgetRepository = budgetRepository;
        this.budgetMemberRepository = budgetMemberRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Budget createBudget(UUID creatorUserId, String name) {
        User creator = userRepository.findById(creatorUserId).orElseThrow();
        Budget budget = new Budget();
        budget.setName(name);
        budget.setCreatedAt(Instant.now());
        Budget savedBudget = budgetRepository.save(budget);

        BudgetMember member = new BudgetMember();
        member.setBudget(savedBudget);
        member.setUser(creator);
        member.setRole(BudgetRole.ADMIN);
        member.setCreatedAt(Instant.now());
        budgetMemberRepository.save(member);

        setActiveBudget(creator.getId(), savedBudget.getId());
        ensureDefaultAccounts(savedBudget, creator);

        return savedBudget;
    }

    @Transactional
    public void setActiveBudget(UUID userId, UUID budgetId) {
        UserSettings settings = userSettingsRepository.findById(userId).orElseThrow();
        settings.setActiveBudget(budgetRepository.findById(budgetId).orElseThrow());
        settings.setUpdatedAt(Instant.now());
        userSettingsRepository.save(settings);
    }

    @Transactional(readOnly = true)
    public UUID getActiveBudgetId(UUID userId) {
        return userSettingsRepository.findById(userId)
                .map(s -> s.getActiveBudget() == null ? null : s.getActiveBudget().getId())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Budget> listBudgetsForUser(UUID userId) {
        return budgetMemberRepository.findBudgetsByUserId(userId);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(UUID budgetId, UUID userId) {
        return budgetMemberRepository.hasRole(budgetId, userId, BudgetRole.ADMIN);
    }

    @Transactional
    public void addMemberByTelegramUserId(UUID budgetId, long telegramUserId, BudgetRole role) {
        User user = userRepository.findByTelegramUserId(telegramUserId).orElseThrow();
        if (budgetMemberRepository.findMembership(budgetId, user.getId()).isPresent()) {
            return;
        }
        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        BudgetMember member = new BudgetMember();
        member.setBudget(budget);
        member.setUser(user);
        member.setRole(role);
        member.setCreatedAt(Instant.now());
        budgetMemberRepository.save(member);
        ensureDefaultAccounts(budget, user);
    }

    @Transactional
    public void removeMemberByTelegramUserId(UUID budgetId, long telegramUserId) {
        User user = userRepository.findByTelegramUserId(telegramUserId).orElseThrow();
        BudgetMember member = budgetMemberRepository.findMembership(budgetId, user.getId()).orElseThrow();
        budgetMemberRepository.delete(member);
    }

    @Transactional(readOnly = true)
    public List<BudgetMember> listMembers(UUID budgetId) {
        return budgetMemberRepository.findAllByBudgetId(budgetId);
    }

    @Transactional
    public void ensureDefaultAccounts(Budget budget, User user) {
        accountRepository.findByBudgetIdAndName(budget.getId(), "Cash:" + user.getTelegramUserId())
                .orElseGet(() -> {
                    Account cash = new Account();
                    cash.setBudget(budget);
                    cash.setOwnerUser(user);
                    cash.setName("Cash:" + user.getTelegramUserId());
                    cash.setType(AccountType.CASH);
                    cash.setCurrency("BYN");
                    cash.setCreatedAt(Instant.now());
                    return accountRepository.save(cash);
                });
    }
}


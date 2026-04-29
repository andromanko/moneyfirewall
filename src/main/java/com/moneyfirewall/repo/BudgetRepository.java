package com.moneyfirewall.repo;

import com.moneyfirewall.domain.Budget;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {}


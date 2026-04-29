package com.moneyfirewall.repo;

import com.moneyfirewall.domain.ImportSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportSessionRepository extends JpaRepository<ImportSession, UUID> {
    @Query("select s from ImportSession s where s.budget.id = :budgetId and s.sha256 = :sha256")
    Optional<ImportSession> findByBudgetIdAndSha256(@Param("budgetId") UUID budgetId, @Param("sha256") String sha256);
}


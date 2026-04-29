package com.moneyfirewall.repo;

import com.moneyfirewall.domain.AssetEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetEventRepository extends JpaRepository<AssetEvent, UUID> {
    @Query("""
            select e from AssetEvent e
            where e.budget.id = :budgetId
              and e.occurredAt >= :from
              and e.occurredAt < :to
            order by e.occurredAt asc
            """)
    List<AssetEvent> findAllInRange(@Param("budgetId") UUID budgetId, @Param("from") Instant from, @Param("to") Instant to);
}


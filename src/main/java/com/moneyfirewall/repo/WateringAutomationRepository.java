package com.moneyfirewall.repo;

import com.moneyfirewall.domain.WateringAutomation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WateringAutomationRepository extends JpaRepository<WateringAutomation, UUID> {}


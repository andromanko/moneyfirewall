package com.moneyfirewall.service;

import com.moneyfirewall.domain.WateringAutomation;
import com.moneyfirewall.repo.WateringAutomationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WateringAutomationService {
    private final WateringAutomationRepository repo;

    public WateringAutomationService(WateringAutomationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public WateringAutomation getOrCreate(UUID userId) {
        return repo.findById(userId).orElseGet(() -> {
            WateringAutomation a = new WateringAutomation();
            a.setUserId(userId);
            a.setEnabled(false);
            a.setIntervalMinutes(60);
            a.setUpdatedAt(Instant.now());
            return repo.save(a);
        });
    }

    @Transactional
    public WateringAutomation setEnabled(UUID userId, boolean enabled) {
        WateringAutomation a = getOrCreate(userId);
        a.setEnabled(enabled);
        a.setUpdatedAt(Instant.now());
        return repo.save(a);
    }

    @Transactional
    public WateringAutomation setIntervalMinutes(UUID userId, int minutes) {
        WateringAutomation a = getOrCreate(userId);
        a.setIntervalMinutes(minutes);
        a.setUpdatedAt(Instant.now());
        return repo.save(a);
    }
}


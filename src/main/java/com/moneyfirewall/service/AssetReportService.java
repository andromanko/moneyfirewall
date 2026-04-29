package com.moneyfirewall.service;

import com.moneyfirewall.domain.AssetEvent;
import com.moneyfirewall.domain.AssetEventType;
import com.moneyfirewall.repo.AssetEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetReportService {
    private final AssetEventRepository assetEventRepository;

    public AssetReportService(AssetEventRepository assetEventRepository) {
        this.assetEventRepository = assetEventRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> positions(UUID budgetId, Instant from, Instant to) {
        List<AssetEvent> events = assetEventRepository.findAllInRange(budgetId, from, to);
        Map<String, BigDecimal> pos = new HashMap<>();
        for (AssetEvent e : events) {
            BigDecimal q = e.getQuantity();
            BigDecimal sign = switch (e.getEventType()) {
                case BUY, TRANSFER_IN, INTEREST -> BigDecimal.ONE;
                case SELL, TRANSFER_OUT -> BigDecimal.ONE.negate();
                case FEE -> BigDecimal.ZERO;
            };
            pos.merge(e.getAssetSymbol(), q.multiply(sign), BigDecimal::add);
        }
        return pos;
    }
}

